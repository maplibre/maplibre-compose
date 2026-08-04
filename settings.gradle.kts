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
    // TODO(maplibre-native-ffi): drop once maplibre-native-ffi has a release.
    maven {
      url = uri("https://central.sonatype.com/repository/maven-snapshots/")
      content { includeGroup("org.maplibre.nativeffi") }
    }
  }
}

// Versions: https://plugins.gradle.org/plugin/org.gradle.toolchains.foojay-resolver-convention
plugins { id("org.gradle.toolchains.foojay-resolver-convention") version ("1.0.0") }

include(
  ":",
  ":demo-app",
  ":glfw-fixture",
  ":lib",
  ":lib:maplibre-compose",
  ":lib:maplibre-compose-material3",
  ":lib:maplibre-js-bindings",
  ":lib:maplibre-compose-gms",
)
