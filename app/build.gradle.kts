import java.util.Base64

plugins {
    id("com.android.application")
    kotlin("android")
}

// CI injects these from the pushed git tag (v1.2.3 -> VERSION_NAME=1.2.3).
val ciVersionName: String? = System.getenv("VERSION_NAME")
val ciVersionCode: Int? = System.getenv("VERSION_CODE")?.toIntOrNull()
val keystoreB64: String? = System.getenv("KEYSTORE_BASE64")

android {
    namespace = "io.leancast.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "io.leancast"
        minSdk = 21
        targetSdk = 28
        versionCode = ciVersionCode ?: 1
        versionName = ciVersionName ?: "0.1.0"
    }

    signingConfigs {
        if (!keystoreB64.isNullOrEmpty()) {
            create("ci") {
                val ksFile = rootProject.file("ci-release.keystore")
                Base64.getDecoder().decode(keystoreB64).inputStream().use { input ->
                    ksFile.outputStream().use { output -> input.copyTo(output) }
                }
                storeFile = ksFile
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS") ?: "leancast"
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Signed with the CI keystore when provided; otherwise falls back to
            // the debug key so the release APK is still sideloadable.
            signingConfig = if (!keystoreB64.isNullOrEmpty()) {
                signingConfigs.getByName("ci")
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

    lint {
        // targetSdk 28 is a deliberate choice for TV sideload distribution
        // (documented in openspec design); this app is not published on Play.
        disable += "ExpiredTargetSdkVersion"
    }
}

dependencies {
    implementation(project(":domain"))

    implementation("org.nanohttpd:nanohttpd:2.3.1")

    implementation("androidx.media3:media3-exoplayer:1.3.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.3.1")
    implementation("androidx.media3:media3-ui:1.3.1")

    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")

    testImplementation("junit:junit:4.13.2")
}
