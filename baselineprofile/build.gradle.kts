// Generates the app's baseline profile on a connected device or emulator.
//
// Opt-in, because it needs a device and because the profile tooling trails AGP releases: included only
// when the build is run with -PbaselineProfile (see settings.gradle.kts), so an ordinary build of the
// app can never be broken by it. To regenerate:
//
//     ./gradlew -PbaselineProfile :baselineprofile:connectedAndroidTest
//
// then copy the generated `*-baseline-prof.txt` from baselineprofile/build/outputs/ over
// app/src/main/baseline-prof.txt, which ProfileInstaller applies on install.
plugins {
    // From the AGP jar the app's own plugin already put on the classpath.
    id("com.android.test")
    // Versioned here rather than declared at the root, so an ordinary build never resolves it.
    alias(libs.plugins.baselineprofile)
}

android {
    namespace = "com.ruskcoder.messages.baselineprofile"
    compileSdk = 36

    defaultConfig {
        // Profiles are collected on API 28+, where the platform can record them.
        minSdk = 28
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    targetProjectPath = ":app"
}

baselineProfile {
    useConnectedDevices = true
}

dependencies {
    implementation(libs.androidx.junit.ext)
    implementation(libs.androidx.uiautomator)
    implementation(libs.androidx.benchmark.macro.junit4)
}
