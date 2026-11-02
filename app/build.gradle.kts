plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// CI passes these in; local builds fall back to a dev version.
val appVersionName = (findProperty("appVersionName") as String?) ?: "0.0.0-dev"
val appVersionCode = (findProperty("appVersionCode") as String?)?.toInt() ?: 1

// Release signing comes from environment variables (set from GitHub Actions secrets).
val keystorePath: String? = System.getenv("SIGNING_KEYSTORE_PATH")

android {
    namespace = "io.github.barneydobson.ntstv"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.barneydobson.ntstv"
        minSdk = 26
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName
    }

    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                val storePass = System.getenv("SIGNING_STORE_PASSWORD")
                storeFile = file(keystorePath)
                storePassword = storePass
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")?.ifBlank { null } ?: "ntstv"
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")?.ifBlank { null } ?: storePass
                enableV2Signing = true
                // v3 allows a future key rotation (apksigner rotate) without a reinstall.
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Without a release key (e.g. pull-request builds) fall back to the debug key
            // so the APK is still installable for testing.
            signingConfig = if (keystorePath != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    buildFeatures {
        buildConfig = true
    }

    // The encrypted dependency report is only useful to Google Play; leave it out.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-session:1.5.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-guava:1.9.0")
    implementation("io.coil-kt:coil:2.7.0")
}
