rootProject.name = "maplibre-compose-project"

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
  repositories {
    google {
      @Suppress("UnstableApiUsage")
      mavenContent {
        includeGroupAndSubgroups("androidx")
        includeGroupAndSubgroups("com.android")
        includeGroupAndSubgroups("com.google")
      }
    }
    mavenCentral()
    gradlePluginPortal()
  }
}

dependencyResolutionManagement {
  @Suppress("UnstableApiUsage")
  repositories {
    google {
      mavenContent {
        includeGroupAndSubgroups("androidx")
        includeGroupAndSubgroups("com.android")
        includeGroupAndSubgroups("com.google")
      }
    }
    mavenCentral()
  }
}

// Versions: https://plugins.gradle.org/plugin/org.gradle.toolchains.foojay-resolver-convention
plugins { id("org.gradle.toolchains.foojay-resolver-convention") version ("1.0.0") }

// When set to a published version string (e.g. "0.7.0"), the native JNI library is
// downloaded from Maven Central instead of being built from the C++ submodule.
// Safe for Kotlin-only changes outside lib/maplibre-native-bindings[-jni].
// Set in ~/.gradle/gradle.properties to avoid accidentally committing it.
val prebuiltJniVersion: String? =
  providers.gradleProperty("prebuiltJniVersion").orNull?.takeIf { it.isNotBlank() }

include(
  ":",
  ":demo-app",
  ":lib",
  ":lib:maplibre-compose",
  ":lib:maplibre-compose-material3",
  ":lib:maplibre-native-bindings",
  ":lib:maplibre-js-bindings",
  ":lib:maplibre-compose-gms",
)

if (prebuiltJniVersion == null) {
  include(":lib:maplibre-native-bindings-jni")
}
