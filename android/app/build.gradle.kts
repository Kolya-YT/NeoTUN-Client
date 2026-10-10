plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.neotun.app"
    compileSdk = 35

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }

    packaging { jniLibs { useLegacyPackaging = true } }

    // Ship device-specific APKs so each install contains only its own native engines.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = false
        }
    }

    defaultConfig {
        applicationId = "com.neotun.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 40
        versionName = "0.6.0"
    }

    signingConfigs {
        create("release") {
            val keystorePath = System.getenv("NEOTUN_KEYSTORE_PATH")
            val keystorePassword = System.getenv("NEOTUN_KEYSTORE_PASSWORD")
            val keyAlias = System.getenv("NEOTUN_KEY_ALIAS")
            val keyPassword = System.getenv("NEOTUN_KEY_PASSWORD")
            if (!keystorePath.isNullOrBlank() && !keystorePassword.isNullOrBlank() && !keyAlias.isNullOrBlank() && !keyPassword.isNullOrBlank()) {
                storeFile = file(keystorePath)
                storePassword = keystorePassword
                this.keyAlias = keyAlias
                this.keyPassword = keyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            val hasProductionKey = !System.getenv("NEOTUN_KEYSTORE_PATH").isNullOrBlank()
            signingConfig = if (hasProductionKey) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }

    sourceSets["main"].jniLibs.srcDir("src/main/jniLibs")
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-ktx:1.10.0")
    implementation("com.github.singbox-android:libbox:1.14.1")
}
