plugins {
    alias(libs.plugins.android.application)
    // No kotlin.android plugin: AGP 9 has built-in Kotlin. See AGENTS.md.
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.example.mediabackup"
    // API 37 is only published as minor-versioned platforms (android-37.0/.1/.2) and
    // only on the canary SDK channel. AGP 9.4 supports up to 37. compose 1.11+ and
    // androidx.core 1.19+ require compiling against 37.
    compileSdk {
        version = release(37) {
            minorApiLevel = 2
        }
    }

    defaultConfig {
        applicationId = "com.example.mediabackup"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        vectorDrawables.useSupportLibrary = true
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            // Personal-use build: R8 off so the AWS SDK needs no keep rules, and signed
            // with this machine's debug key so the APK installs on your phone immediately.
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // No kotlin { compilerOptions { jvmTarget = ... } } needed: with built-in Kotlin
    // the Kotlin jvmTarget defaults to android.compileOptions.targetCompatibility.

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    lint {
        abortOnError = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.work.runtime.ktx)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.aws.android.sdk.s3)
    // CognitoCachingCredentialsProvider + the Cognito Identity client live in
    // aws-android-sdk-core, which aws-android-sdk-s3 brings in transitively.

    testImplementation(libs.junit)

    // The AWS SDK declares very old versions of some transitive dependencies. These
    // constraints lift them to current stable without forking or replacing the SDK.
    // A constraint only raises a version that is already on the classpath, so this
    // cannot introduce anything new.
    constraints {
        // aws-android-sdk-s3:2.81.1 -> appcompat:1.2.0 (2020) -> fragment:1.1.0 (2020)
        implementation(libs.androidx.appcompat)
        // aws-android-sdk-core:2.81.1 -> gson:2.8.9 (2021)
        implementation(libs.gson)
    }
}
