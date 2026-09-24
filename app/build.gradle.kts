plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.shellzero"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.shellzero"
        minSdk = 26
        targetSdk = 34
        versionCode = 4
        versionName = "2.0.0"

        // Only arm64-v8a per spec (strictly no x86)
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    signingConfigs {
        create("release") {
            // Use debug keystore for now to ensure installable APK.
            // For production, replace with a dedicated release keystore:
            // storeFile = file("release.keystore")
            storeFile = file(System.getProperty("user.home") + "/.android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
            isV1SigningEnabled = true
            isV2SigningEnabled = true
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }
    // Window insets for ExtraKeys bar imePadding()
    // Ensure edge-to-edge if needed
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.activity:activity-compose:1.9.3")

    // Compose BOM
    val composeBom = platform("androidx.compose:compose-bom:2024.10.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3:1.3.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")

    // For Debian tar.xz extraction
    implementation("org.apache.commons:commons-compress:1.26.2")
    implementation("org.tukaani:xz:1.9")

    // Optional: full VT emulator (recommended for production)
    // implementation("com.termux:terminal-emulator:0.118")
    // implementation("com.termux:terminal-view:0.118")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
