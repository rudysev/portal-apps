import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.kotlin.jvm)
  // Android Lint on a pure-JVM module, as portal-commons' :commons does.
  alias(libs.plugins.android.lint)
  alias(libs.plugins.kotlinter)
  alias(libs.plugins.detekt)
}

// Java 11 bytecode so the app (AGP compileOptions 11) can consume it, whatever JDK runs the build.
java {
  sourceCompatibility = JavaVersion.VERSION_11
  targetCompatibility = JavaVersion.VERSION_11
}

kotlin {
  compilerOptions {
    jvmTarget.set(JvmTarget.JVM_11)
    // Keep the build warning-free: any compiler warning fails compilation.
    allWarningsAsErrors.set(true)
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
}

// sherpa-onnx's JVM binding and this host's native library, fetched by ../fetch-deps.sh (not on Maven Central).
val sherpaJvm = fileTree("libs") { include("*.jar") }

dependencies {
  // The ai.onnxruntime API only: the app supplies onnxruntime-android at runtime, the tests the JVM build.
  compileOnly(libs.onnxruntime.jvm)

  testImplementation(libs.junit)
  testImplementation(libs.onnxruntime.jvm)
  testImplementation(sherpaJvm)
}

tasks.test {
  useJUnit()
  testLogging { events("passed", "skipped", "failed") }
  // The real models the app ships, for the model-backed tests.
  val assets = rootProject.file("app/src/main/assets")
  systemProperty("cascade.assets", assets.path)
  inputs.dir(assets).withPropertyName("assets").withPathSensitivity(PathSensitivity.RELATIVE)
  // Optional local TTS clips (README "Validating locally"); the positive-speech tests skip without them.
  val fixtures = rootProject.file("fixtures")
  systemProperty("cascade.fixtures", fixtures.path)
  if (fixtures.isDirectory) {
    inputs.dir(
      fixtures,
    ).withPropertyName("fixtures").withPathSensitivity(PathSensitivity.RELATIVE)
  }
  val jars = files(sherpaJvm)
  doFirst {
    check(jars.files.size == 2 && assets.resolve("oww").isDirectory && assets.resolve("kws").isDirectory) {
      "Models or sherpa-onnx JVM jars missing: run ./fetch-deps.sh first"
    }
  }
}

// detekt on the defaults plus the shared opt-ins in config/detekt/detekt.yml (identical to the submodules').
detekt {
  buildUponDefaultConfig = true
  config.setFrom(rootProject.file("config/detekt/detekt.yml"))
}

// `check` runs the type-resolving tasks (detektMain/detektTest) instead of the plugin's default `detekt` task.
tasks.named("detekt") { enabled = false }
tasks.check { dependsOn("detektMain", "detektTest") }
