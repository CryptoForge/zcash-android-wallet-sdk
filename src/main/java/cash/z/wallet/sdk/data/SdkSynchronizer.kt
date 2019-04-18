package cash.z.wallet.sdk.data

import cash.z.wallet.sdk.dao.WalletTransaction
import cash.z.wallet.sdk.data.SdkSynchronizer.SyncState.*
import cash.z.wallet.sdk.exception.SynchronizerException
import cash.z.wallet.sdk.secure.Wallet
import kotlinx.coroutines.*
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.distinct
import kotlin.coroutines.CoroutineContext

/**
 * The glue. Downloads compact blocks to the database and then scans them for transactions. In order to serve that
 * purpose, this class glues together a variety of key components. Each component contributes to the team effort of
 * providing a simple source of truth to interact with.
 *
 * Another way of thinking about this class is the reference that demonstrates how all the pieces can be tied
 * together.
 *
 * @param downloader the component that downloads compact blocks and exposes them as a stream
 * @param processor the component that saves the downloaded compact blocks to the cache and then scans those blocks for
 * data related to this wallet.
 * @param repository the component that exposes streams of wallet transaction information.
 * @param activeTransactionManager the component that manages the lifecycle of active transactions. This includes sent
 * transactions that have not been mined.
 * @param wallet the component that wraps the JNI layer that interacts with librustzcash and manages wallet config.
 * @param batchSize the number of compact blocks to download at a time.
 * @param staleTolerance the number of blocks to allow before considering our data to be stale
 * @param blockPollFrequency how often to poll for compact blocks. Once all missing blocks have been downloaded, this
 * number represents the number of milliseconds the synchronizer will wait before checking for newly mined blocks.
 */
class SdkSynchronizer(
    private val downloader: CompactBlockStream,
    private val processor: CompactBlockProcessor,
    private val repository: TransactionRepository,
    private val activeTransactionManager: ActiveTransactionManager,
    private val wallet: Wallet,
    private val batchSize: Int = 1000,
    private val staleTolerance: Int = 10,
    private val blockPollFrequency: Long = CompactBlockStream.DEFAULT_POLL_INTERVAL
) : Synchronizer {

    /**
     * The primary job for this Synchronizer. It leverages structured concurrency to cancel all work when the
     * `parentScope` provided to the [start] method ends.
     */
    private lateinit var blockJob: Job

    /**
     * The state this Synchronizer was in when it started. This is helpful because the conditions that lead to FirstRun
     * or isStale being detected can change quickly so retaining the initial state is useful for walkthroughs or other
     * elements of an app that need to rely on this information later, rather than in realtime.
     */
    private lateinit var initialState: SyncState

    /**
     * Returns true when `start` has been called on this synchronizer.
     */
    private val wasPreviouslyStarted
        get() = ::blockJob.isInitialized

    /**
     * Retains the error that caused this synchronizer to fail for future error handling or reporting.
     */
    private var failure: Throwable? = null

    /**
     * The default exception handler for the block job. Calls [onException].
     */
    private val exceptionHandler: (c: CoroutineContext, t: Throwable) -> Unit = { _, t -> onException(t) }

    /**
     * Sets a listener to be notified of uncaught Synchronizer errors. When null, errors will only be logged.
     */
    override var onSynchronizerErrorListener: ((Throwable?) -> Boolean)? = null
        set(value) {
            field = value
            if (failure != null) value?.invoke(failure)
        }


    //
    // Public API
    //

    /* Lifecycle */

    /**
     * Starts this synchronizer within the given scope. For simplicity, attempting to start an instance that has already
     * been started will throw a [SynchronizerException.FalseStart] exception. This reduces the complexity of managing
     * resources that must be recycled. Instead, each synchronizer is designed to have a long lifespan and should be
     * started from an activity, application or session.
     *
     * @param parentScope the scope to use for this synchronizer, typically something with a lifecycle such as an
     * Activity for single-activity apps or a logged in user session. This scope is only used for launching this
     * synchronzer's job as a child.
     */
    override fun start(parentScope: CoroutineScope): Synchronizer {
        //  prevent restarts so the behavior of this class is easier to reason about
        if (wasPreviouslyStarted) throw SynchronizerException.FalseStart
        twig("starting")
        failure = null
        blockJob = parentScope.launch(CoroutineExceptionHandler(exceptionHandler)) {
            supervisorScope {
                continueWithState(determineState())
            }
        }
        return this
    }

    /**
     * Stops this synchronizer by stopping the downloader, repository, and activeTransactionManager, then cancelling the
     * parent job. Note that we do not cancel the parent scope that was passed into [start] because the synchronizer
     * does not own that scope, it just uses it for launching children.
     */
    override fun stop() {
        twig("stopping")
        downloader.stop().also { twig("downloader stopped") }
        repository.stop().also { twig("repository stopped") }
        activeTransactionManager.stop().also { twig("activeTransactionManager stopped") }
        // TODO: investigate whether this is necessary and remove or improve, accordingly
        Thread.sleep(5000L)
        blockJob.cancel().also { twig("blockJob cancelled") }
    }


    /* Channels */

    /**
     * A stream of all the wallet transactions, delegated to the [activeTransactionManager].
     */
    override fun activeTransactions() = activeTransactionManager.subscribe()

    /**
     * A stream of all the wallet transactions, delegated to the [repository].
     */
    override fun allTransactions(): ReceiveChannel<List<WalletTransaction>> {
        return repository.allTransactions()
    }

    /**
     * A stream of progress values, corresponding to this Synchronizer downloading blocks, delegated to the
     * [downloader]. Any non-zero value below 100 indicates that progress indicators can be shown and a value of 100
     * signals that progress is complete and any progress indicators can be hidden. At that point, the synchronizer
     * switches from catching up on missed blocks to periodically monitoring for newly mined blocks.
     */
    override fun progress(): ReceiveChannel<Int> {
        return downloader.progress()
    }

    /**
     * A stream of balance values, delegated to the [wallet].
     */
    override fun balance(): ReceiveChannel<Wallet.WalletBalance> {
        return wallet.balance()
    }


    /* Status */

    /**
     * A flag to indicate that this Synchronizer is significantly out of sync with it's server. This is determined by
     * the delta between the current block height reported by the server and the latest block we have stored in cache.
     * Whenever this delta is greater than the [staleTolerance], this function returns true. This is intended for
     * showing progress indicators when the user returns to the app after having not used it for a long period.
     * Typically, this means the user may have to wait for downloading to occur and the current balance and transaction
     * information cannot be trusted as 100% accurate.
     *
     * @return true when the local data is significantly out of sync with the remote server and the app data is stale.
     */
    override suspend fun isStale(): Boolean = withContext(IO) {
        val latestBlockHeight = downloader.connection.getLatestBlockHeight()
        val ourHeight = processor.cacheDao.latestBlockHeight()
        val tolerance = 10
        val delta = latestBlockHeight - ourHeight
        twig("checking whether out of sync. " +
                "LatestHeight: $latestBlockHeight  ourHeight: $ourHeight  Delta: $delta   tolerance: $tolerance")
        delta > tolerance
    }

    /**
     * A flag to indicate that the initial state of this synchronizer was firstRun. This is useful for knowing whether
     * initializing the database is required and whether to show things like"first run walk-throughs."
     *
     * @return true when this synchronizer has not been run before on this device or when cache has been cleared since
     * the last run.
     */
    override suspend fun isFirstRun(): Boolean = withContext(IO) {
        initialState is FirstRun
    }


    /* Operations */

    /**
     * Gets the address for the given account.
     *
     * @param accountId the optional accountId whose address of interest. By default, the first account is used.
     */
    override fun getAddress(accountId: Int): String = wallet.getAddress()

    /**
     * Sends zatoshi.
     *
     * @param zatoshi the amount of zatoshi to send.
     * @param toAddress the recipient's address.
     * @param memo the optional memo to include as part of the transaction.
     * @param fromAccountId the optional account id to use. By default, the first account is used.
     */
    override suspend fun sendToAddress(zatoshi: Long, toAddress: String, memo: String, fromAccountId: Int) =
        activeTransactionManager.sendToAddress(zatoshi, toAddress, memo, fromAccountId)

    /**
     * Attempts to cancel a previously sent transaction. Transactions can only be cancelled during the calculation phase
     * before they've been submitted to the server. This method will return false when it is too late to cancel. This
     * logic is delegated to the activeTransactionManager, which knows the state of the given transaction.
     *
     * @param transaction the transaction to cancel.
     * @return true when the cancellation request was successful. False when it is too late to cancel.
     */
    override fun cancelSend(transaction: ActiveSendTransaction): Boolean = activeTransactionManager.cancel(transaction)


    //
    // Private API
    //

    /**
     * After determining the initial state, continue based on those findings.
     *
     * @param syncState the sync state found
     */
    private fun CoroutineScope.continueWithState(syncState: SyncState): Job {
        return when (syncState) {
            FirstRun -> onFirstRun()
            is CacheOnly -> onCacheOnly(syncState)
            is ReadyToProcess -> onReady(syncState)
        }
    }

    /**
     * Logic for the first run. This is when the wallet gets initialized, which includes setting up the dataDB and
     * preloading it with data corresponding to the wallet birthday.
     */
    private fun CoroutineScope.onFirstRun(): Job {
        twig("this appears to be a fresh install, beginning first run of application")
        val firstRunStartHeight = wallet.initialize() // should get the latest sapling tree and return that height
        twig("wallet firstRun returned a value of $firstRunStartHeight")
        return continueWithState(ReadyToProcess(firstRunStartHeight))
    }

    /**
     * Logic for starting the Synchronizer when no scans have yet occurred. Takes care of initializing the dataDb and
     * then
     */
    private fun CoroutineScope.onCacheOnly(syncState: CacheOnly): Job {
        twig("we have cached blocks but no data DB, beginning pre-cached version of application")
        val firstRunStartHeight = wallet.initialize(syncState.startingBlockHeight)
        twig("wallet has already cached up to a height of $firstRunStartHeight")
        return continueWithState(ReadyToProcess(firstRunStartHeight))
    }

    /**
     * Logic for starting the Synchronizer once it is ready for processing. All starts eventually end with this method.
     */
    private fun CoroutineScope.onReady(syncState: ReadyToProcess) = launch {
        twig("synchronization is ready to begin at height ${syncState.startingBlockHeight}")
        // TODO: for PIR concerns, introduce some jitter here for where, exactly, the downloader starts
        val blockChannel =
            downloader.start(
                this,
                syncState.startingBlockHeight,
                batchSize,
                pollFrequencyMillis = blockPollFrequency
            )
        launch { monitorProgress(downloader.progress()) }
        launch { monitorTransactions(repository.allTransactions().distinct()) }
        activeTransactionManager.start()
        repository.start(this)
        processor.processBlocks(blockChannel)
    }

    /**
     * Monitor download progress in order to trigger a scan the moment all blocks have been received. This reduces the
     * amount of time it takes to get accurate balance information since scan intervals are fairly long.
     */
    private suspend fun monitorProgress(progressChannel: ReceiveChannel<Int>) = withContext(IO) {
        twig("beginning to monitor download progress")
        for (i in progressChannel) {
            if(i >= 100) {
                twig("triggering a proactive scan in a second because all missing blocks have been loaded")
                delay(1000L)
                launch {
                    twig("triggering proactive scan!")
                    processor.scanBlocks()
                    twig("done triggering proactive scan!")
                }
                break
            }
        }
        twig("done monitoring download progress")
    }

    /**
     * Monitors transactions and recalculates the balance any time transactions have changed.
     */
    private suspend fun monitorTransactions(transactionChannel: ReceiveChannel<List<WalletTransaction>>) =
        withContext(IO) {
            twig("beginning to monitor transactions in order to update the balance")
            for (i in transactionChannel) {
                twig("triggering a balance update because transactions have changed")
                wallet.sendBalanceInfo()
                twig("done triggering balance check!")
            }
            twig("done monitoring transactions in order to update the balance")
        }

    /**
     * Determine the initial state of the data by checking whether the dataDB is initialized and the last scanned block
     * height. This is considered a first run if no blocks have been processed.
     */
    private suspend fun determineState(): SyncState = withContext(IO) {
        twig("determining state (has the app run before, what block did we last see, etc.)")
        initialState = if (processor.dataDbExists) {
            val isInitialized = repository.isInitialized()
            // this call blocks because it does IO
            val startingBlockHeight = Math.max(processor.lastProcessedBlock(), repository.lastScannedHeight())

            twig("cacheDb exists with last height of $startingBlockHeight and isInitialized = $isInitialized")
            if (!repository.isInitialized()) FirstRun else ReadyToProcess(startingBlockHeight)
        } else if(processor.cachDbExists) {
            // this call blocks because it does IO
            val startingBlockHeight = processor.lastProcessedBlock()
            twig("cacheDb exists with last height of $startingBlockHeight")
            if (startingBlockHeight <= 0) FirstRun else CacheOnly(startingBlockHeight)
        } else {
            FirstRun
        }

        twig("determined ${initialState::class.java.simpleName}")
         initialState
    }

    /**
     * Wraps exceptions, logs them and then invokes the [onSynchronizerErrorListener], if it exists.
     */
    private fun onException(throwable: Throwable) {
        twig("********")
        twig("********  ERROR: $throwable")
        if (throwable.cause != null) twig("******** caused by ${throwable.cause}")
        if (throwable.cause?.cause != null) twig("******** caused by ${throwable.cause?.cause}")
        twig("********")

        val hasRecovered = onSynchronizerErrorListener?.invoke(throwable)
        if (hasRecovered != true) stop().also { failure = throwable }
    }

    /**
     * Represents the initial state of the Synchronizer.
     */
    sealed class SyncState {
        /**
         * State for the first run of the Synchronizer, when the database has not been initialized.
         */
        object FirstRun : SyncState()

        /**
         * State for when compact blocks have been downloaded but not scanned. This state is typically achieved when the
         * app was previously started but killed before the first scan took place. In this case, we do not need to
         * download compact blocks that we already have.
         *
         * @param startingBlockHeight the last block that has been downloaded into the cache. We do not need to download
         * any blocks before this height because we already have them.
         */
        class CacheOnly(val startingBlockHeight: Int = Int.MAX_VALUE) : SyncState()

        /**
         * The final state of the Synchronizer, when all initialization is complete and the starting block is known.
         *
         * @param startingBlockHeight the height that will be fed to the downloader. In most cases, it will represent
         * either the wallet birthday or the last block that was processed in the previous session.
         */
        class ReadyToProcess(val startingBlockHeight: Int = Int.MAX_VALUE) : SyncState()
    }

}