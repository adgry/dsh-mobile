import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Signing details stay out of the build script so the key can be swapped without editing it.
val signingProps = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

// Credentials that get baked into the APK live in an untracked file, so the repository can be
// public without publishing a working key. CI passes the same value through an env var.
val secretProps = Properties().apply {
    val file = rootProject.file("secrets.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val defaultApiKey: String =
    System.getenv("DSH_DEFAULT_API_KEY") ?: secretProps.getProperty("defaultApiKey") ?: ""

// Version can be overridden per build, so a test or a release can be cut without editing the file:
//   gradle assembleRelease -PdshVersionName=1.3.1 -PdshVersionCode=10301
// versionCode follows major*10000 + minor*100 + patch, matching what CI derives from the tag, so
// a locally built APK and a CI-published one of the same version agree.
val appVersionName = (project.findProperty("dshVersionName") as String?)?.takeIf { it.isNotBlank() }
    ?: "1.3.0"
val appVersionCode = (project.findProperty("dshVersionCode") as String?)?.toIntOrNull() ?: 10_300

android {
    namespace = "com.dshmobile.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.dshmobile.app"
        minSdk = 26
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
        vectorDrawables { useSupportLibrary = true }

        buildConfigField("String", "DEFAULT_API_KEY", "\"" + defaultApiKey + "\"")
    }

    signingConfigs {
        val storePath = signingProps.getProperty("storeFile")
        if (!storePath.isNullOrBlank() && rootProject.file(storePath).exists()) {
            create("release") {
                storeFile = rootProject.file(storePath)
                storePassword = signingProps.getProperty("storePassword")
                keyAlias = signingProps.getProperty("keyAlias")
                keyPassword = signingProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
        }
        release {
            // Falls back to the debug key so `assembleRelease` still works on a fresh clone
            // that has no keystore.properties.
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/DEPENDENCIES",
                "/META-INF/*.kotlin_module",
            )
        }
    }
}

// Drops the signed APK somewhere stable and human-named, instead of the buildDirectory path.
val releaseApkName = "dsh-mobile-$appVersionName.apk"
tasks.register<Copy>("collectRelease") {
    dependsOn("assembleRelease")
    from(layout.buildDirectory.file("outputs/apk/release/app-release.apk"))
    into(rootProject.layout.projectDirectory.dir("release"))
    rename { releaseApkName }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
}
