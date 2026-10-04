plugins {
    id("com.android.application")
    id("com.google.devtools.ksp")
    id("androidx.room")
}

val releaseKeystorePath = providers.environmentVariable("ANDROID_KEYSTORE_PATH").orNull
val releaseKeystorePassword = providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD").orNull
val releaseKeyAlias = providers.environmentVariable("ANDROID_KEY_ALIAS").orNull
val releaseKeyPassword = providers.environmentVariable("ANDROID_KEY_PASSWORD").orNull
val releaseSigningValues = listOf(
    releaseKeystorePath,
    releaseKeystorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
)
val hasReleaseSigning = releaseSigningValues.all { !it.isNullOrBlank() }

check(releaseSigningValues.none { !it.isNullOrBlank() } || hasReleaseSigning) {
    "Release signing requires all ANDROID_KEYSTORE_* and ANDROID_KEY_* environment variables"
}

android {
    namespace = "com.keithvassallo.ncmediaprovider"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.keithvassallo.ncmediaprovider"
        minSdk = 34
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(requireNotNull(releaseKeystorePath))
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            signingConfig = signingConfigs.findByName("release")
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
        aidl = true
        buildConfig = true
        viewBinding = true
    }

    // The dependency-info block is encrypted with a Google key; F-Droid rejects APKs carrying it.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    // MigrationTestHelper reads the exported schemas as assets (PLAN 9.6). The Room plugin adds them
    // to instrumented tests only, and an app's JVM tests get the app's own assets, so the debug
    // build carries them: about 100 KB of JSON in the debug APK, none in release.
    sourceSets.getByName("debug").assets.directories.add("$projectDir/schemas")

    testOptions {
        unitTests.isReturnDefaultValues = true
        // Robolectric tests (the Room store) need Android resources on the classpath.
        unitTests.isIncludeAndroidResources = true
        // Robolectric reaches into FileDescriptor through JDK internals that JDK 17+ closes by
        // default; on SDK 37 its set-up needs jdk.internal.access as well.
        unitTests.all {
            it.jvmArgs(
                "--add-opens=java.base/java.io=ALL-UNNAMED",
                "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED",
            )
        }
    }
}

// Exported schemas let every database migration be tested (PLAN 9.6).
room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    implementation("androidx.activity:activity-ktx:1.13.0")
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("androidx.browser:browser:1.9.0")
    // Video playback in the picker's preview (PLAN 5.4).
    implementation("androidx.media3:media3-exoplayer:1.11.0")
    implementation("androidx.media3:media3-datasource-okhttp:1.11.0")
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("com.google.android.material:material:1.14.0")
    implementation("com.squareup.okhttp3:okhttp:5.5.0")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("androidx.room:room-runtime:2.8.5")
    ksp("androidx.room:room-compiler:2.8.5")
    implementation("androidx.work:work-runtime-ktx:2.12.0")

    // On-phone tests against a test server (PLAN 5.1); run with `am instrument`, see docs/device-notes.md.
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")

    testImplementation("junit:junit:4.13.2")
    // Android's org.json is only a stub in JVM unit tests.
    testImplementation("org.json:json:20250517")
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("androidx.test:core:1.7.0")
    // Database migration tests (PLAN 9.6).
    testImplementation("androidx.room:room-testing:2.8.5")
}
