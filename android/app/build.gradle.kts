import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    FileInputStream(localPropertiesFile).use { input ->
        localProperties.load(input)
    }
}

fun getSigningValue(name: String): String? {
    val envValue = System.getenv(name)?.trim()
    if (!envValue.isNullOrEmpty()) return envValue
    val propValue = localProperties.getProperty(name)?.trim()
    if (!propValue.isNullOrEmpty()) return propValue
    return null
}

val signingStoreFilePath = getSigningValue("ANYCLAW_SIGNING_STORE_FILE")
val signingStorePassword = getSigningValue("ANYCLAW_SIGNING_STORE_PASSWORD")
val signingKeyAlias = getSigningValue("ANYCLAW_SIGNING_KEY_ALIAS")
val signingKeyPassword = getSigningValue("ANYCLAW_SIGNING_KEY_PASSWORD")

val hasFixedSigning =
    !signingStoreFilePath.isNullOrEmpty() &&
    !signingStorePassword.isNullOrEmpty() &&
    !signingKeyAlias.isNullOrEmpty() &&
    !signingKeyPassword.isNullOrEmpty() &&
    file(signingStoreFilePath).exists()

android {
    namespace = "com.codex.mobile"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.codex.mobile.beta"
        minSdk = 24
        // targetSdk 28 allows executing binaries from app data directory.
        // Android 10+ (targetSdk 29+) enforces W^X which blocks this via SELinux.
        // Termux (F-Droid) uses the same approach.
        targetSdk = 28
        versionCode = 34
        versionName = "0.2.4-beta-shizuku2-prompt1-fixedsign1"
    }

    signingConfigs {
        if (hasFixedSigning) {
            create("fixed") {
                storeFile = file(signingStoreFilePath!!)
                storePassword = signingStorePassword
                keyAlias = signingKeyAlias
                keyPassword = signingKeyPassword
                if (signingStoreFilePath.endsWith(".p12", ignoreCase = true) ||
                    signingStoreFilePath.endsWith(".pfx", ignoreCase = true)
                ) {
                    storeType = "pkcs12"
                }
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasFixedSigning) {
                signingConfig = signingConfigs.getByName("fixed")
            }
        }
        debug {
            if (hasFixedSigning) {
                signingConfig = signingConfigs.getByName("fixed")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    // Don't compress bootstrap zip or server bundle in assets
    androidResources {
        noCompress += listOf("zip", "tar.gz")
    }

}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("com.google.android.material:material:1.12.0")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    implementation("org.nanohttpd:nanohttpd:2.3.1")
}
