// Imported rather than fully qualified: inside a Kotlin DSL script `java` resolves to Gradle's own
// `java` extension, which shadows the package and makes `java.util.Properties` unresolvable.
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    // No standalone org.jetbrains.kotlin.android: AGP 9's built-in Kotlin support provides it.
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

/**
 * Which ABIs to build the Rust core for.
 *
 * Declared up here because `defaultConfig` reads it: a Kotlin DSL script runs top to bottom, so a
 * `val` defined further down is still null by the time the android block is evaluated.
 */
val rustAbis: List<String> =
    (project.findProperty("rustAbis") as String? ?: "arm64-v8a")
        .split(",").map { it.trim() }.filter { it.isNotEmpty() }

android {
    namespace = "com.ruskcoder.messages"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.ruskcoder.messages"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            // Only the ABIs the Rust core was actually built for. Without this the APK advertises
            // every ABI its other dependencies happen to ship — and would install on a device that
            // has no `libimessage_core.so`, then crash the moment it touched iMessage.
            abiFilters += rustAbis
        }
    }

    lint {
        // Lint's Kotlin FIR resolver crashes building light classes for some @Composable
        // signatures (KotlinIllegalArgumentExceptionWithAttachments in ChatScreen.kt). That is a
        // bug in lint, not in the sources, so it must not fail the build.
        abortOnError = false
        checkReleaseBuilds = false
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("debug")
            // R8: dead code out, the rest renamed and inlined. The rules that matter are for the Rust
            // bridge, which JNA reaches by reflection where R8 cannot see it — see proguard-rules.pro.
            // The ABI split is left to the app bundle, which already delivers each phone only the
            // native libraries for its own architecture.
            optimization {
                enable = true
                keepRules {
                    files.add(file("proguard-rules.pro"))
                }
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        aidl = true
    }

    // whisper.cpp, for on-device transcription — see src/main/cpp/CMakeLists.txt. Built only for
    // the ABIs `abiFilters` above allows, which is the same list the Rust core is built for.
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

// ---------------------------------------------------------------------------
// Rust core (iMessage)
// ---------------------------------------------------------------------------
//
// Two steps, both driven from `rust/`:
//
//  1. `cargo ndk` cross-compiles `imessage-core` for each requested ABI and drops the resulting
//     `.so` straight into `src/main/jniLibs/<abi>/`, which AGP already packages as-is.
//  2. `uniffi-bindgen` reads the exported metadata back out of one of those `.so` files and emits
//     the Kotlin that calls into it.
//
// The bindgen lives in its own crate (`rust/uniffi-bindgen`) rather than as a `[[bin]]` on
// `imessage-core`, so generating bindings never needs a *host* build of rustpush — which on Windows
// would drag in vendored OpenSSL, perl and nasm for no reason.
//
// ABIs default to arm64-v8a (every current physical device) plus x86_64 (the emulator). Each extra
// ABI is a full cross-compile of ~600 crates, so narrow it when iterating:
//
//     ./gradlew assembleDebug -PrustAbis=arm64-v8a

/** The NDK the Rust half is built against. Kept in step with what Android Studio has installed. */
val ndkVersionForRust = "28.2.13676358"

val rustRoot = rootProject.layout.projectDirectory.dir("rust")
val rustCoreDir = rustRoot.dir("imessage-core").asFile
val bindgenManifest = rustRoot.dir("uniffi-bindgen").file("Cargo.toml").asFile


/** Rust's target triple per Android ABI, needed to locate the built artifact. */
val rustAbiTriples = mapOf(
    "arm64-v8a" to "aarch64-linux-android",
    "armeabi-v7a" to "armv7-linux-androideabi",
    "x86" to "i686-linux-android",
    "x86_64" to "x86_64-linux-android",
)

val jniLibsDir = layout.projectDirectory.dir("src/main/jniLibs")
// Computing Mac validation data on the device needs a closed-source backend that is not part of this
// repository. When its checkout is present at `private/`, the Rust core is built with it
// (`--features native-nac`); otherwise the core is built against the public stub, and registering
// takes a validation relay code.
val privateNacDir = rootProject.layout.projectDirectory.dir("private/open-absinthe").asFile
val nativeNac = File(privateNacDir, "src/emulator.rs").isFile

// The backend links Unicorn dynamically, because its QEMU-derived C core must be configured with the
// NDK toolchain directly. A matching prebuilt is required for every requested ABI; fail during
// Gradle configuration with a useful message instead of much later at the Rust linker.
if (nativeNac) {
    val missingUnicornAbis = rustAbis.filter {
        !File(jniLibsDir.asFile, "$it/libunicorn.so").isFile
    }
    check(missingUnicornAbis.isEmpty()) {
        "No prebuilt Unicorn library for ${missingUnicornAbis.joinToString()}. " +
            "Build libunicorn.so with the Android NDK toolchain and add it under jniLibs."
    }
}

// Resolved to a plain File rather than left as a Provider: AGP 9 refuses Providers in the source
// set API, because it cannot tell generated directories from editable ones. The task dependency
// that a Provider would have carried is declared explicitly on KotlinCompile below.
val uniffiOutDir: File = layout.buildDirectory.dir("generated/uniffi").get().asFile

/**
 * Where the NDK lives, for cargo-ndk.
 *
 * Resolved by hand rather than through AGP's `sdkComponents`, which cannot be instantiated in AGP 9
 * outside a variant callback. Checks the conventional environment variables first, then falls back
 * to the SDK location Android Studio wrote into `local.properties`.
 */
fun resolveNdkDir(): String {
    sequenceOf("ANDROID_NDK_HOME", "ANDROID_NDK_ROOT")
        .mapNotNull { System.getenv(it) }
        .firstOrNull { File(it).isDirectory }
        ?.let { return it }

    val sdkDir = sequenceOf("ANDROID_HOME", "ANDROID_SDK_ROOT")
        .mapNotNull { System.getenv(it) }
        .firstOrNull { File(it).isDirectory }
        ?: rootProject.file("local.properties")
            .takeIf { it.exists() }
            ?.let { file ->
                Properties()
                    .apply { file.inputStream().use { stream -> load(stream) } }
                    .getProperty("sdk.dir")
            }
        ?: error("Cannot find the Android SDK. Set ANDROID_HOME or sdk.dir in local.properties.")

    val candidate = File(sdkDir, "ndk/$ndkVersionForRust")
    check(candidate.isDirectory) {
        "NDK $ndkVersionForRust is not installed at $candidate. " +
            "Install it, or set ANDROID_NDK_HOME to the one you want."
    }
    return candidate.absolutePath
}

/**
 * A Perl that emits Unix-style paths, for OpenSSL's `Configure`.
 *
 * Vendored OpenSSL refuses to configure under Strawberry Perl — it reports itself as `MSWin32` and
 * produces backslash paths, and `Configure` bails with "this perl implementation doesn't produce
 * Unix like paths". Git for Windows ships an MSYS Perl that does, but which of the two wins depends
 * on the PATH of whatever launched the build: a Git Bash shell finds the right one, Android Studio
 * finds Strawberry. Pinning it here makes the build behave the same either way.
 *
 * Returns the *directory* holding it, to be prepended to PATH rather than injected as an absolute
 * path: `C:\Program Files\Git\...` contains a space, and OpenSSL's generated Makefile does not
 * quote `$(PERL)` in every rule it uses it from. Putting the directory on PATH instead lets Perl be
 * invoked by bare name, which is exactly how a working Git Bash build already reaches it.
 *
 * Returns null off Windows, and when no Git Perl is installed — in both cases whatever is on PATH
 * is likely already fine.
 */
fun resolveUnixPerlDir(): String? {
    if (!System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) return null

    // Git's own layout: `<git>/cmd/git.exe` alongside `<git>/usr/bin/perl.exe`.
    val fromPath = System.getenv("PATH").orEmpty()
        .split(File.pathSeparator)
        .asSequence()
        .map { File(it) }
        .filter { it.name.equals("cmd", true) || it.name.equals("bin", true) }
        .map { File(it.parentFile, "usr/bin/perl.exe") }
        .firstOrNull { it.isFile }
    if (fromPath != null) return fromPath.parentFile.absolutePath

    return sequenceOf(
        "C:/Program Files/Git/usr/bin/perl.exe",
        "C:/Program Files (x86)/Git/usr/bin/perl.exe",
    ).map { File(it) }.firstOrNull { it.isFile }?.parentFile?.absolutePath
}

/**
 * Whether to cross-compile the Rust core on this invocation.
 *
 * Off by default once the libraries exist. A full cross-compile is ~600 crates including vendored
 * OpenSSL and runs for over ten minutes, which is far too long to sit inside every ordinary Kotlin
 * build. Gradle's own up-to-date checks are not enough on their own here: `openssl-sys` declares
 * `rerun-if-env-changed` on its compiler variables, so merely building from a different shell can
 * send cargo back to the start.
 *
 * Rebuild after changing anything under `rust/`:
 *
 *     ./gradlew assembleDebug -PbuildRust
 */
val rustBuildRequested = project.hasProperty("buildRust")
val rustLibrariesPresent = rustAbis.all {
    File(jniLibsDir.asFile, "$it/libimessage_core.so").isFile
}

val cargoBuildRust by tasks.registering(Exec::class) {
    group = "rust"
    description = "Cross-compiles imessage-core for ${rustAbis.joinToString()}"

    // Resolved here rather than inside the predicate: a lambda written directly against script
    // properties captures the script object, which the configuration cache cannot serialize.
    // Missing libraries still force a build — an APK without them cannot run.
    val shouldBuild = rustBuildRequested || !rustLibrariesPresent
    onlyIf { shouldBuild }

    workingDir = rustCoreDir

    // Only crate sources matter; `target/` is cargo's own cache and must not invalidate this.
    inputs.files(
        fileTree(rustRoot) {
            include("**/*.rs", "**/*.toml", "**/*.proto", "**/Cargo.lock")
            exclude("**/target/**")
        }
    )
    inputs.property("nativeNac", nativeNac)
    if (nativeNac) {
        inputs.files(fileTree(privateNacDir) { exclude("**/target/**") })
        inputs.files(rustAbis.map { File(jniLibsDir.asFile, "$it/libunicorn.so") })
    }
    outputs.dir(jniLibsDir)

    val ndkDir = resolveNdkDir()
    val unixPerlDir = resolveUnixPerlDir()
    val unicornLibDirsByTargetEnv = rustAbis.associate { abi ->
        val triple = rustAbiTriples[abi]
            ?: error("Unknown ABI '$abi'. Known: ${rustAbiTriples.keys.joinToString()}")
        "UNICORN_LIB_DIR_${triple.replace('-', '_')}" to
            File(jniLibsDir.asFile, abi).absolutePath
    }
    doFirst {
        environment("ANDROID_NDK_HOME", ndkDir)
        // cargo-ndk normally emits Windows paths for the compiler tools. Vendored OpenSSL feeds
        // those paths through MSYS make/sh, where unescaped backslashes are consumed and turn
        // `toolchains\\llvm` into `toolchainsllvm`. cargo-ndk deliberately normalizes its generated
        // environment when it detects an MSYS host, so advertise the MSYS toolchain we put on PATH
        // below. This is also what makes the same build work when launched from Git Bash.
        if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true) &&
            System.getenv("MSYSTEM").isNullOrBlank()
        ) {
            environment("MSYSTEM", "MINGW64")
        }
        // Put the MSYS Perl ahead of any Strawberry Perl rather than naming it outright. OpenSSL
        // bakes whichever Perl ran `Configure` into its Makefile, and an absolute Windows path with
        // a space in it breaks the rules that use it unquoted.
        unixPerlDir?.let {
            environment("PATH", it + File.pathSeparator + System.getenv("PATH").orEmpty())
        }
        // The patched system-binding crate links the prebuilt matching the Cargo target. This
        // avoids running bindgen/pkg-config or compiling Unicorn's C core during normal app builds.
        if (nativeNac) {
            unicornLibDirsByTargetEnv.forEach { (name, path) -> environment(name, path) }
        }
    }

    commandLine(
        listOf("cargo", "ndk") +
            rustAbis.flatMap { listOf("-t", it) } +
            listOf(
                "--platform", "24",
                "-o", jniLibsDir.asFile.absolutePath,
                "build", "--release",
            ) +
            if (nativeNac) listOf("--features", "native-nac") else emptyList()
    )
}

val generatedBindings = File(uniffiOutDir, "uniffi/imessage_core/imessage_core.kt")

val generateUniffiBindings by tasks.registering(Exec::class) {
    group = "rust"
    description = "Generates Kotlin bindings from the built imessage-core library"

    // Regenerate when the library was just rebuilt, or when the bindings are simply not there —
    // `clean` wipes them, since they live under `build/`.
    val shouldGenerate = rustBuildRequested || !rustLibrariesPresent || !generatedBindings.isFile
    onlyIf { shouldGenerate }

    dependsOn(cargoBuildRust)
    // Must run from the crate directory: bindgen shells out to `cargo metadata` to resolve the
    // library back to its package, and that needs a manifest in the working directory.
    workingDir = rustCoreDir

    // The exported interface is identical across ABIs, so read it from whichever was built first.
    val triple = rustAbiTriples[rustAbis.first()]
        ?: error("Unknown ABI '${rustAbis.first()}'. Known: ${rustAbiTriples.keys.joinToString()}")
    val library = File(rustCoreDir, "target/$triple/release/libimessage_core.so")

    inputs.file(library)
    outputs.dir(uniffiOutDir)

    // `--library` is a flag, not an option: the library path is the positional SOURCE argument and
    // has to come last.
    commandLine(
        "cargo", "run", "--release",
        "--manifest-path", bindgenManifest.absolutePath,
        "--", "generate",
        "--library",
        "--language", "kotlin",
        "--out-dir", uniffiOutDir.absolutePath,
        library.absolutePath,
    )
}

android {
    sourceSets.getByName("main") {
        // Generated rather than checked in, so it cannot drift from the .so it binds to.
        kotlin.srcDir(uniffiOutDir)
        jniLibs.srcDir(jniLibsDir)
    }
}

// Both generated directories are declared as plain paths rather than task outputs — AGP 9 refuses
// Providers in the source set API — so the dependencies a Provider would have carried are declared
// here instead. Matched by name to avoid needing AGP's and KSP's task classes on the build script's
// classpath.
//
// Anything reading the `main` Kotlin source set sees the generated bindings, which is the compiler
// and KSP alike; anything packaging native libraries needs the `.so` to exist first.
tasks.matching { it.name.startsWith("compile") || it.name.startsWith("ksp") }.configureEach {
    dependsOn(generateUniffiBindings)
}
tasks.matching { it.name.contains("JniLib") }.configureEach {
    dependsOn(cargoBuildRust)
}

// ---------------------------------------------------------------------------
// Speech model (on-device transcription)
// ---------------------------------------------------------------------------
//
// Not packaged, and not downloaded by the build either. The model is fetched by the app, on request,
// into its own storage — see `SpeechModelStore`. Bundling it made every install and every update
// carry tens of megabytes for a feature most sessions never touch, and made the choice of model the
// build's to make rather than the user's.

check(File(projectDir, "src/main/cpp/whisper.cpp/CMakeLists.txt").isFile) {
    "The whisper.cpp submodule is missing. Run: git submodule update --init --recursive"
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.coil.compose)
    implementation(libs.coil.core)
    implementation(libs.coil.video)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.common)
    implementation(libs.androidx.media3.ui)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.material)
    // UniFFI's generated Kotlin calls the Rust library through JNA rather than hand-written JNI.
    implementation(libs.jna) { artifact { type = "aar" } }
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.androidx.camera.mlkit.vision)
    implementation(libs.mlkit.barcode.scanning)
    implementation(libs.zxing.core)
    implementation(libs.okhttp)
    implementation(libs.reorderable)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)
    implementation(libs.androidx.profileinstaller)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
