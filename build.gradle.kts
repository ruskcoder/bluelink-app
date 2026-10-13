// Top-level build file where you can add configuration options common to all sub-projects/modules.

// AGP 9's built-in Kotlin support otherwise resolves kotlin-stdlib to whatever version Gradle's
// own embedded Kotlin pins (2.4.0 here), which is newer than KSP's latest release supports
// (com.google.devtools.ksp tops out at 2.3.11 as of this writing). Pinning the Kotlin Gradle
// plugin explicitly keeps the whole toolchain — built-in Kotlin, the Compose/serialization
// compiler plugins, and KSP — on one version that all of them actually support.
buildscript {
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.0")
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
}
