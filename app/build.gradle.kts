import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

// Optional keys come from Codemagic environment variables or local.properties (your PC).
// They are OPTIONAL: if empty, the app asks for them on the login screen and stores them encrypted.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun secret(name: String): String = (System.getenv(name) ?: localProps.getProperty(name) ?: "").trim()

// Release signing uses ONLY the keystore attached in Codemagic (android_signing in codemagic.yaml).
// Codemagic provides CM_KEYSTORE_PATH, CM_KEYSTORE_PASSWORD, CM_KEY_ALIAS, CM_KEY_PASSWORD. No default/debug key.
val ksPath: String? = System.getenv("CM_KEYSTORE_PATH")?.takeIf { it.isNotBlank() }

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

gradle.taskGraph.whenReady {
    val releaseBuild = allTasks.any { it.name == "assembleRelease" }
    if (releaseBuild && ksPath == null) {
        throw GradleException(
            "Release keystore not found. In codemagic.yaml the workflow needs  android_signing: - <your keystore reference name>  (the name of the keystore you uploaded to Codemagic)."
        )
    }
}
