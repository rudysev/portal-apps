plugins {
  alias(libs.plugins.android.application) apply false
  alias(libs.plugins.android.lint) apply false
  alias(libs.plugins.kotlin.jvm) apply false
  // Only puts KGP `libs.versions.kotlin` on the build classpath, overriding the older KGP that AGP's built-in Kotlin
  // depends on. Never applied: AGP 9 registers the Kotlin support itself (see app/build.gradle.kts).
  alias(libs.plugins.kotlin.android) apply false
  // ktlint via kotlinter (ADR 0001), applied here for the Gradle-script lint below.
  alias(libs.plugins.kotlinter)
  alias(libs.plugins.detekt) apply false
  base
}

// kotlinter's lintKotlin only covers source sets, so lint the Gradle scripts here.
val lintKotlinScripts = tasks.register<org.jmailen.gradle.kotlinter.tasks.LintTask>("lintKotlinScripts") {
  source(fileTree(rootDir) { include("*.gradle.kts", "*/*.gradle.kts") })
}
tasks.check { dependsOn(lintKotlinScripts) }
val formatKotlinScripts = tasks.register<org.jmailen.gradle.kotlinter.tasks.FormatTask>("formatKotlinScripts") {
  source(fileTree(rootDir) { include("*.gradle.kts", "*/*.gradle.kts") })
}
// The root has no formatKotlin of its own, so `./gradlew formatKotlin` also fixes the Gradle scripts.
tasks.register("formatKotlin") { dependsOn(formatKotlinScripts) }
