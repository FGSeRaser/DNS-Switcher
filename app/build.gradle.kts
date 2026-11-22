plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "de.fgseraser.dnsswitcher"
    compileSdk = 35

    defaultConfig {
        applicationId = "de.fgseraser.dnsswitcher"
        minSdk = 28 // Private DNS exists since Android 9
        targetSdk = 35
        // GitHub Actions sets GITHUB_RUN_NUMBER, so every build gets a higher version
        val lauf = (System.getenv("GITHUB_RUN_NUMBER") ?: "0").toInt()
        versionCode = 100 + lauf
        versionName = "1.1.$lauf"
    }

    signingConfigs {
        // Optional: own key from the CI secret (e.g. your debug.keystore, so updates install over the old app)
        val datei = System.getenv("DNSSWITCHER_KEYSTORE_DATEI")
        if (!datei.isNullOrBlank()) {
            create("eigen") {
                storeFile = file(datei)
                storePassword = System.getenv("DNSSWITCHER_KEYSTORE_PASS") ?: "android"
                keyAlias = System.getenv("DNSSWITCHER_KEY_ALIAS") ?: "androiddebugkey"
                keyPassword = System.getenv("DNSSWITCHER_KEY_PASS") ?: (System.getenv("DNSSWITCHER_KEYSTORE_PASS") ?: "android")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Own key if available, otherwise the debug key so "Build > Build APK" gives an installable APK
            signingConfig = signingConfigs.findByName("eigen") ?: signingConfigs.getByName("debug")
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
        buildConfig = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // Shizuku: grant WRITE_SECURE_SETTINGS without PC
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}
