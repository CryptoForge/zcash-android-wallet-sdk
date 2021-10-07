plugins {
    id("com.android.library")
    id("zcash.android-build-conventions")
    id("kotlin-android")
    id("kotlin-kapt")
}

android {
    defaultConfig {
        //targetSdk = 30 //Integer.parseInt(project.property("targetSdkVersion"))
        multiDexEnabled = true
    }

    // Need to figure out how to move this into the build-conventions
    kotlinOptions {
        jvmTarget = libs.versions.java.get()
        allWarningsAsErrors = project.property("IS_TREAT_WARNINGS_AS_ERRORS").toString().toBoolean()
    }

    packagingOptions {
        resources.excludes.addAll(
            listOf("META-INF/AL2.0", "META-INF/LGPL2.1",)
        )
    }

    // Placing this in the build-conventions does not appear to work
    testOptions {
        animationsDisabled = true

        if (project.property("IS_USE_TEST_ORCHESTRATOR").toString().toBoolean()) {
            execution = "ANDROIDX_TEST_ORCHESTRATOR"
        }
    }
}

dependencies {
    implementation(projects.sdkLib)
    implementation(libs.kotlin.stdlib)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.multidex)
    implementation(libs.bundles.grpc)

    androidTestImplementation(libs.bundles.androidx.test)
    androidTestImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.zcashwalletplgn)
    androidTestImplementation(libs.bip39)

    // Need to figure out the right syntax to move this into build-conventions
    if (project.property("IS_USE_TEST_ORCHESTRATOR").toString().toBoolean()) {
        androidTestUtil(libs.androidx.testOrchestrator) {
            artifact {
                type = "apk"
            }
        }
    }
}
