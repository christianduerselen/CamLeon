import java.util.Properties
import org.gradle.api.GradleException

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// ---- Version from Nerdbank.GitVersioning (version.json + git height) -------------------------------
// Requires the nbgv CLI:  dotnet tool install -g nbgv
// versionName = SemVer2 (e.g. "1.0.0" on main / release tags, "1.0.0-g1a2b3c4d" on other branches)
// versionCode = major*10_000_000 + minor*100_000 + patch*1_000 + git height (height < 1000)
data class AppVersion(val name: String, val code: Int)

fun nbgvVersion(): AppVersion {
    return try {
        val out = providers.exec {
            commandLine("nbgv", "get-version", "-f", "json")
            workingDir = rootDir
        }.standardOutput.asText.get()
        fun field(n: String) = Regex("\"$n\"\\s*:\\s*\"?([^\",}]+)\"?").find(out)?.groupValues?.get(1)
            ?: throw GradleException("nbgv output without $n")
        val major = field("VersionMajor").toInt()
        val minor = field("VersionMinor").toInt()
        val patch = field("BuildNumber").toInt()
        val height = field("VersionHeight").toInt().coerceAtMost(999)
        AppVersion(field("SemVer2"), major * 10_000_000 + minor * 100_000 + patch * 1_000 + height)
    } catch (e: Exception) {
        logger.warn("nbgv not available (${e.message}) - using 0.0.0-dev")
        AppVersion("0.0.0-dev", 1)
    }
}
val appVersion = nbgvVersion()

// ---- Release signing: keystore.properties (NOT in git) points to the keystore outside the repo ---------
val signingProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "de.camleon"
    compileSdk = 36

    defaultConfig {
        applicationId = "de.camleon.app"
        minSdk = 29
        targetSdk = 36
        versionCode = appVersion.code
        versionName = appVersion.name
        ndk { abiFilters += "arm64-v8a" }
    }

    signingConfigs {
        if (signingProps.getProperty("storeFile") != null) create("release") {
            storeFile = file(signingProps.getProperty("storeFile"))
            storePassword = signingProps.getProperty("storePassword")
            keyAlias = signingProps.getProperty("keyAlias")
            keyPassword = signingProps.getProperty("keyPassword")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
        debug {
            applicationIdSuffix = ".debug"   // debug and release can be installed side by side
            versionNameSuffix = "-debug"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        jniLibs { useLegacyPackaging = true }
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")

    // RTMP push + recording (encoder, muxer).
    implementation("com.github.pedroSG94.RootEncoder:library:2.4.7")
    // WebRTC (prebuilt libwebrtc, org.webrtc.*): camera owner + low-latency browser preview.
    implementation("io.getstream:stream-webrtc-android:1.3.10")
    // Embedded LAN control server (browser page + JSON API).
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    // QR code of the control URL in the phone UI.
    implementation("com.google.zxing:core:3.5.3")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    testImplementation("junit:junit:4.13.2")
}
