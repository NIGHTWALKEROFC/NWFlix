import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

// Secrets come from environment variables (Codemagic) or local.properties (your PC).
// They are OPTIONAL: if empty, the app asks for them on the login screen and stores them encrypted.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun secret(name: String): String = (System.getenv(name) ?: localProps.getProperty(name) ?: "").trim()

// Build only one CPU type:  ./gradlew assembleRelease -Pabi=arm64-v8a
// Build all + universal:    ./gradlew assembleRelease            (default)
val abiProp: String? = (project.findProperty("abi") as String?)?.trim()?.ifEmpty { null }
val isBundleBuild = gradle.startParameter.taskNames.any { it.contains("bundle", ignoreCase = true) }

android {
    namespace = "app.nwmovie"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.nwmovie"
        minSdk = 24
        targetSdk = 35
        versionCode = (System.getenv("BUILD_NUMBER")?.toIntOrNull() ?: 1)
        versionName = "1.0.0"
        val tgId = secret("TG_API_ID").ifEmpty { "0" }.filter { it.isDigit() }.ifEmpty { "0" }
        buildConfigField("int", "TG_API_ID", tgId)
        buildConfigField("String", "TG_API_HASH", "\"${secret("TG_API_HASH")}\"")
        buildConfigField("String", "TMDB_API_KEY", "\"${secret("TMDB_API_KEY")}\"")
    }

    splits {
        abi {
            isEnable = !isBundleBuild
            reset()
            if (abiProp == null || abiProp == "universal") {
                include("armeabi-v7a", "arm64-v8a", "x86_64")
                isUniversalApk = true
            } else {
                include(abiProp)
                isUniversalApk = false
            }
        }
    }

    signingConfigs {
        create("release") {
            val path = System.getenv("CM_KEYSTORE_PATH")
            if (path != null) {
                storeFile = file(path)
                storePassword = System.getenv("CM_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("CM_KEY_ALIAS")
                keyPassword = System.getenv("CM_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = (project.findProperty("minify") as String?) != "false"
            isShrinkResources = isMinifyEnabled
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // No keystore configured? Fall back to the debug key so the APK still installs on your phone.
            signingConfig = if (System.getenv("CM_KEYSTORE_PATH") != null)
                signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    packaging { resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/DEPENDENCIES") }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-process:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Player (free, Apache-2.0)
    val media3 = "1.5.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-exoplayer-dash:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    implementation("androidx.media3:media3-session:$media3")
    implementation("androidx.media3:media3-common:$media3")

    // Telegram TDLib (prebuilt Android binaries on Maven Central, Boost licence)
    implementation("com.github.tdlibx:td:1.8.56")

    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
}
