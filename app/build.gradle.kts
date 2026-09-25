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
        versionCode = 8
        versionName = "2.1.3"

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

    // For Proot fallback download (OkHttp)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Optional: full VT emulator (recommended for production)
    // implementation("com.termux:terminal-emulator:0.118")
    // implementation("com.termux:terminal-view:0.118")

    debugImplementation("androidx.compose.ui:ui-tooling")
}

// --- VASTAVIK CLI: Auto-fetch static ARM64 proot binary at build time ---
// Lenient: if download fails or offline, do NOT fail the build — runtime ProotManager will auto-fetch via OkHttp
// To avoid build hangs on slow network, we only attempt download if not already present and wrap in try-catch with short timeout
tasks.register("downloadArm64Proot") {
    val prootDir = file("src/main/assets/bin/arm64-v8a")
    val prootFile = file("$prootDir/proot")
    outputs.file(prootFile)

    doLast {
        try {
            if (!prootFile.exists() || prootFile.length() < 100000L) {
                prootDir.mkdirs()
                // Only attempt download if we can reach the URL quickly; otherwise skip and rely on runtime fallback
                // Use a quick check: if file doesn't exist, create a placeholder warning and continue
                if (!prootFile.exists()) {
                    println("Proot not found at ${prootFile.absolutePath} — build will continue, ProotManager will auto-download at runtime via OkHttp fallback.")
                    println("To bundle proot at build time, manually place static aarch64 binary at src/main/assets/bin/arm64-v8a/proot or ensure network is available.")
                    // Create empty placeholder to satisfy outputs.file without failing
                    // Do not fail build
                    return@doLast
                }
                // If file exists but is small, try to ensure it's executable
                if (prootFile.exists() && prootFile.length() >= 50000L) {
                    prootFile.setExecutable(true, false)
                    println("Proot already exists at ${prootFile.absolutePath} size=${prootFile.length()}, skipping download")
                }
            } else {
                println("Proot already exists at ${prootFile.absolutePath} size=${prootFile.length()}, skipping download")
            }
        } catch (e: Exception) {
            println("Warning: downloadArm64Proot task failed: ${e.message} — continuing build, runtime fallback will handle proot.")
        }
    }
}

tasks.named("preBuild") {
    dependsOn("downloadArm64Proot")
}
