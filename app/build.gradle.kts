plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// Release signing is configured only when all four variables are set and the keystore exists.
// Otherwise the release build falls back to the debug key so that assembleRelease always succeeds.
fun envOrNull(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }

val releaseStorePath: String? = envOrNull("SIGNING_STORE_FILE")
val releaseStorePassword: String? = envOrNull("SIGNING_STORE_PASSWORD")
val releaseKeyAlias: String? = envOrNull("SIGNING_KEY_ALIAS")
val releaseKeyPassword: String? = envOrNull("SIGNING_KEY_PASSWORD")
val releaseStoreFile: File? = releaseStorePath?.let { file(it) }?.takeIf { it.isFile }
val hasReleaseSigning: Boolean = releaseStoreFile != null &&
    releaseStorePassword != null &&
    releaseKeyAlias != null &&
    releaseKeyPassword != null

if (!hasReleaseSigning) {
    logger.info("Release signing variables not set: release build uses the debug signing key.")
}

android {
    namespace = "com.example.spheresweeper"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.spheresweeper"
        minSdk = 24
        targetSdk = 34
        versionCode = 3
        versionName = "0.3.0"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = releaseStoreFile
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = if (hasReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
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

    buildFeatures {
        compose = true
        buildConfig = false
    }

    composeOptions {
        kotlinCompilerExtensionVersion = libs.versions.composeCompiler.get()
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
}
