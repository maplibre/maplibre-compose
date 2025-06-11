@file:Suppress("UnstableApiUsage")

rootProject.name = "maplibre-compose"

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
  repositories {
    google {
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
  repositories {
    google {
      mavenContent {
        includeGroupAndSubgroups("androidx")
        includeGroupAndSubgroups("com.android")
        includeGroupAndSubgroups("com.google")
      }
    }
    mavenCentral()
    // https://kevinnzou.github.io/compose-webview-multiplatform/installation/
    maven("https://jogamp.org/deployment/maven")
  }
}

plugins { id("org.gradle.toolchains.foojay-resolver-convention") version ("0.10.0") }

include(
  ":",
  ":demo-app",
  ":lib",
  ":lib:maplibre-compose",
  ":lib:maplibre-compose-material3",
  ":lib:maplibre-compose-expressions",
  ":lib:maplibre-compose-webview",
  ":lib:kotlin-maplibre-js",
  ":lib:compose-html-interop",
)
