pluginManagement {
  repositories {
    google {
      content {
        includeGroupByRegex("com\\.android.*")
        includeGroupByRegex("com\\.google.*")
        includeGroupByRegex("androidx.*")
      }
    }
    mavenCentral()
    gradlePluginPortal()
  }
}

plugins { id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0" }

dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    google()
    mavenCentral()
  }
}

rootProject.name = "Jarvis Cascade"

// Pure JVM: the two-check chain, the openWakeWord stage-1 port and the stage-2 verifier logic, unit-tested on the
// JVM against the real models.
include(":cascade")
// The sideloadable Android test app: capture service, settings UI, on-device candidate log.
include(":app")
