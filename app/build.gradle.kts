import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun secret(name: String): String = (System.getenv(name) ?: localProps.getProperty(name) ?: "").trim()

val ksPath: String? = System.getenv("CM_KEYSTORE_PATH")?.takeIf { it.isNotBlank() }

// version comes from gradle.properties (appVersion=1.0); the version code is derived from it
val appVersion: String = (project.findProperty("appVersion") as String?)?.trim()?.ifEmpty { null } ?: "1.0"
fun codeFor(v: String): Int {
    val n = v.split(".").map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
    return n.getOrElse(0) { 0 } * 1_000_000 + n.getOrElse(1) { 0 } * 1_000 + n.getOrElse(2) { 0 }
}

val abiProp: String? = (project.findProperty("abi") as String?)?.trim()?.ifEmpty { null }
val isBundleBuild = gradle.startParameter.taskNames.any { it.contains("bundle", ignoreCase = true) }

android {
    namespace = "app.nwmovie"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.nightwalker.nwflix"
        minSdk = 24
        targetSdk = 35
        versionCode = codeFor(appVersion)
        versionName = appVersion
        val tgId = secret("TG_API_ID").ifEmpty { "0" }.filter { it.isDigit() }.ifEmpty { "0" }
        buildConfigField("int", "TG_API_ID", tgId)
        buildConfigField("String", "TG_API_HASH", "\"${secret("TG_API_HASH")}\"")
        buildConfigField("String", "TMDB_API_KEY", "\"${secret("TMDB_API_KEY")}\"")
        buildConfigField("String", "UPDATE_URL", "\"${secret("UPDATE_URL")}\"")
        buildConfigField("String", "UPDATE_PUBKEY", "\"${secret("UPDATE_PUBKEY")}\"")
        buildConfigField("String", "CERT_SHA256", "\"${secret("APP_CERT_SHA256")}\"")
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
            if (ksPath != null) {
                storeFile = file(ksPath)
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
            signingConfig = signingConfigs.getByName("release")
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
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
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

    // Player
    val media3 = "1.5.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-exoplayer-dash:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    implementation("androidx.media3:media3-session:$media3")
    implementation("androidx.media3:media3-common:$media3")

    // Telegram TDLib
    implementation("com.github.tdlibx:td:1.8.56")

    // VLC engine
    implementation("org.videolan.android:libvlc-all:3.6.0")

    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
}

gradle.taskGraph.whenReady {
    val releaseBuild = allTasks.any { it.name == "assembleRelease" }
    if (releaseBuild && ksPath == null) {
        throw GradleException("Release keystore not found (CM_KEYSTORE_PATH is empty).")
    }
}
