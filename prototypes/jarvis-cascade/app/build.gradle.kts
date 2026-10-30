plugins {
  // AGP 9 bundles Kotlin support and registers the `kotlin` extension itself, so the standalone
  // org.jetbrains.kotlin.android plugin must NOT be applied (it double-registers and fails).
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlinter)
  alias(libs.plugins.detekt)
}

android {
  namespace = "com.portal.jarviscascade"
  compileSdk = 36

  defaultConfig {
    applicationId = "com.portal.jarviscascade"
    minSdk = 28 // Android 9: gen-1 Portals
    // Sideloaded onto Portal (no Play Store), so Play's target-API floor doesn't apply; 29 is deliberate.
    //noinspection ExpiredTargetSdkVersion
    targetSdk = 29 // Android 10: gen-2 Portals
    versionCode = 1
    versionName = "0.1"
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    // The Portal ABIs (report §6.3: both until each model's ro.product.cpu.abilist is known). A Portal test app,
    // never run on ChromeOS, so no x86_64.
    //noinspection ChromeOsAbiSupport
    ndk { abiFilters += setOf("arm64-v8a", "armeabi-v7a") }
  }

  buildTypes {
    release {
      isMinifyEnabled = false
    }
  }
  // Any lint warning fails the build. No baseline: suppress inline only, with a reason.
  lint {
    warningsAsErrors = true
    abortOnError = true
    checkReleaseBuilds = true
    // Dependency-freshness checks query Maven/Google at lint time, so the result changes whenever upstream ships a
    // release, not when this code changes.
    disable += setOf("AndroidGradlePluginVersion", "GradleDependency", "NewerVersionAvailable")
    // 16 KB page alignment matters from Android 15 on; Portals run Android 9 and 10 (4 KB pages). onnxruntime-android
    // stays on 1.19.2, the version the off-device evaluation measured, whose arm64 library is 4 KB aligned.
    disable += "Aligned16KB"
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  // Prebuilt dependency .so files, packaged as they are: stripping needs an NDK, which nothing here installs.
  // The x86 libraries are dropped before the merge, which otherwise fails on the two x86 libonnxruntime.so files (only
  // sherpa-onnx's x86 build links ONNX Runtime dynamically); the app ships the arm ABIs only.
  packaging {
    jniLibs {
      excludes += setOf("lib/x86/**", "lib/x86_64/**")
      keepDebugSymbols +=
        setOf("**/libsherpa-onnx-jni.so", "**/libonnxruntime.so", "**/libonnxruntime4j_jni.so")
    }
  }
}

kotlin {
  // Keep the build warning-free: any compiler warning fails compilation.
  compilerOptions { allWarningsAsErrors.set(true) }
}

// `check` lints only the default (debug) variant. Lint release too, as portal-wake does.
tasks.check { dependsOn("lintRelease") }

// The models and the sherpa-onnx AAR come from ../fetch-deps.sh; fail early and say so when they're missing.
val sherpaAar = file("libs/sherpa-onnx-static-link-onnxruntime-2.0.1.aar")
val fetchedAssets = listOf("oww/hey_jarvis_v0.1.onnx", "kws/tokens.txt").map { file("src/main/assets/$it") }
val checkFetchedDeps = tasks.register("checkFetchedDeps") {
  val required = fetchedAssets + sherpaAar
  doFirst {
    val missing = required.filterNot { it.isFile }
    check(missing.isEmpty()) { "Missing $missing: run ./fetch-deps.sh first" }
  }
}
tasks.named("preBuild") { dependsOn(checkFetchedDeps) }

dependencies {
  implementation(project(":cascade"))
  // NotificationCompat/ContextCompat.
  implementation(libs.androidx.core)
  // @RequiresApi.
  implementation(libs.androidx.annotation)
  // Stage 1's runtime (the ai.onnxruntime API :cascade compiles against).
  implementation(libs.onnxruntime.android)
  // Stage 2: sherpa-onnx with ONNX Runtime linked statically into libsherpa-onnx-jni.so (no second
  // libonnxruntime.so to clash with onnxruntime-android's). Not on Maven Central; fetched and hash-checked.
  implementation(files(sherpaAar))

  // AndroidChainTest: the chain through the Android bindings on a device or emulator (connectedDebugAndroidTest).
  androidTestImplementation(libs.junit)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.androidx.test.junit)
}

// detekt on the defaults plus the shared opt-ins in config/detekt/detekt.yml.
detekt {
  buildUponDefaultConfig = true
  config.setFrom(rootProject.file("config/detekt/detekt.yml"))
}

// `check` runs the type-resolving tasks (detektMain/detektTest) instead of the plugin's default `detekt` task.
tasks.named("detekt") { enabled = false }
tasks.check { dependsOn("detektMain", "detektTest") }

// The plugin's variant detekt classpath drops the build dependencies of the libraries' artifact transforms, which
// Gradle deprecates; restate it as the FileCollection itself (as portal-wake does).
listOf("Debug", "Release", "DebugUnitTest").forEach { variant ->
  tasks.matching { it.name == "detekt$variant" }.configureEach {
    val kotlinc = tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compile${variant}Kotlin")
    (this as dev.detekt.gradle.Detekt).classpath.setFrom(kotlinc.get().libraries)
  }
}
