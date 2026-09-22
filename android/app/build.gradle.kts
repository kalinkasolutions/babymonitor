plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

/**
 * The commit this was built from, for the About section. Falls back rather than failing: the
 * source may be a tarball or a shallow checkout on somebody else's build server.
 */
fun gitCommit(): String = runCatching {
    ProcessBuilder("git", "rev-parse", "--short=8", "HEAD")
        .directory(rootDir)
        .redirectErrorStream(true)
        .start()
        .inputStream.bufferedReader().readText().trim()
        .takeIf { it.isNotEmpty() && !it.contains(" ") }
        ?: "unknown"
}.getOrDefault("unknown")

android {
    namespace = "ch.lqy.babyphone"
    compileSdk = 36

    defaultConfig {
        applicationId = "ch.lqy.babyphone"
        minSdk = 29
        targetSdk = 36
        // Literals on purpose. F-Droid reads them from here to notice a new version, so this is
        // where a release number lives and the git tag follows it, not the other way round.
        versionCode = 1
        versionName = "0.1"

        buildConfigField("String", "COMMIT", "\"${gitCommit()}\"")
        buildConfigField("String", "SUPPORT_EMAIL", "\"kalinkasolutions@kalinka.ch\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.zxing.core)
    implementation(libs.signalr)
    implementation(libs.webrtc)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    debugImplementation(libs.androidx.ui.tooling)
    testImplementation(libs.junit)
}
