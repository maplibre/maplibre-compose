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

include(
  ":",
  ":demo-app",
  ":demo-app:common",
  ":demo-app:android",
  ":demo-app:desktop",
  ":demo-app:desktop-glfw",
  ":lib",
  ":lib:maplibre-compose",
  ":lib:maplibre-compose-material3",
  ":lib:maplibre-js-bindings",
  ":lib:maplibre-compose-gms",
  ":lib:maplibre-compose-runtime-vulkan-linux-x64",
  ":lib:maplibre-compose-runtime-vulkan-linux-arm64",
  ":lib:maplibre-compose-runtime-metal-macos-arm64",
  ":lib:maplibre-compose-runtime-vulkan-windows-x64",
  ":lib:maplibre-compose-runtime-vulkan-windows-arm64",
)
