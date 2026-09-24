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
    namespace = "ch.kalinka.babymonitor"
    compileSdk = 36

    defaultConfig {
        applicationId = "ch.kalinka.babymonitor"
        minSdk = 29
        targetSdk = 36
        // Literals on purpose. F-Droid reads them from here to notice a new version, so this is
        // where a release number lives and the git tag follows it, not the other way round.
        versionCode = 1
        versionName = "0.0.1-alpha"

        // Shown in Settings, so a phone can say which source it is running. Nothing here may
        // vary between two builds of the same commit — no timestamp, no build number, no host
        // name. The release build is reproducible, which is what lets somebody rebuild the commit
        // an APK claims to be and check it really is (android/verify-apk.py), and a single
        // varying byte here would quietly take that away.
        buildConfigField("String", "COMMIT", "\"${gitCommit()}\"")
        buildConfigField("String", "SUPPORT_EMAIL", "\"kalinkasolutions@kalinka.ch\"")
    }

    /**
     * Release signing, taken from the environment so no keystore and no password ever lives in
     * the repository. The release workflow writes the keystore out of a secret and sets these;
     * a build without them still runs and produces an unsigned APK, which Android will refuse to
     * install — that is the intended outcome for anyone building locally without the key.
     *
     * The keystore has to be the same one every time. Android identifies an app by its signature,
     * so a release signed with a different key is not an upgrade to the one people have: it will
     * not install over it, and the only way out is to uninstall, losing the identity key in the
     * keystore and with it every pairing on that phone.
     */
    signingConfigs {
        System.getenv("ANDROID_KEYSTORE_PATH")?.let { keystore ->
            create("release") {
                storeFile = file(keystore)
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")

                // v1 is the JAR signing nothing has needed since API 24, and minSdk here is 29.
                // v3 is on for the one thing that softens the warning above: it is the scheme
                // that allows the signing key to be rotated later, so a key that has to be
                // replaced need not orphan every install.
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
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
