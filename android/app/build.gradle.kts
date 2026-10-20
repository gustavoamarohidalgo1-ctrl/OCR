import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing comes from android/keystore.properties (ignored by git) or environment variables.
// Without a keystore, assembleRelease still builds an unsigned APK that Android will not install.
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.isFile) file.inputStream().use(::load)
}
fun releaseSigning(property: String, environment: String): String? =
    (keystoreProperties.getProperty(property) ?: System.getenv(environment))?.takeIf { it.isNotBlank() }
val releaseStoreFile = releaseSigning("storeFile", "FACTURA_CLARA_KEYSTORE")

// Tablets only: arm64-v8a, plus armeabi-v7a for older 32-bit devices. No x86/x86_64 in releases.
val tabletAbis = listOf("arm64-v8a", "armeabi-v7a")
// Per-ABI APKs only for release tasks: debug keeps every ABI for emulators and instrumented tests.
val buildingRelease = gradle.startParameter.taskNames.any { it.contains("release", ignoreCase = true) }

android {
    namespace = "com.facturaclara.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.facturaclara.app"
        minSdk = 23
        targetSdk = 35
        versionCode = 10
        versionName = "0.2.8"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    signingConfigs {
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = rootProject.file(releaseStoreFile)
                storePassword = releaseSigning("storePassword", "FACTURA_CLARA_STORE_PASSWORD")
                keyAlias = releaseSigning("keyAlias", "FACTURA_CLARA_KEY_ALIAS")
                keyPassword = releaseSigning("keyPassword", "FACTURA_CLARA_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        release {
            isDebuggable = false
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
            // A release built without naming it (`./gradlew assemble`, `aR`) gets one APK with the tablet
            // ABIs. AGP rejects abiFilters together with ABI splits, so only one of them applies.
            if (!buildingRelease) ndk { abiFilters += tabletAbis }
        }
    }
    splits {
        abi {
            isEnable = buildingRelease
            reset()
            include(*tabletAbis.toTypedArray())
            isUniversalApk = false
        }
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        // Direct offline APK distribution: compress bundled native libraries.
        // Android extracts only the device ABI during installation, without changing models or pixels.
        jniLibs.useLegacyPackaging = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    implementation("androidx.exifinterface:exifinterface:1.4.1")
    // Bundled model: no download at runtime, no cloud account or API key.
    implementation("com.google.mlkit:text-recognition:16.0.1")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    implementation("cz.adaptech.tesseract4android:tesseract4android:4.9.0")
    // 1.20.0 supports API21+; 1.21.1 raises its AAR minimum to API24.
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")
    // Official bundled native image processing, API21+, no external service.
    implementation("org.opencv:opencv:4.14.0")
}
