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
    maven("https://developer.huawei.com/repo/") {
      content { includeGroupAndSubgroups("com.huawei") }
    }
  }
}

include(
  ":",
  ":demo-app",
  ":demo-app:common",
  ":demo-app:android",
  ":demo-app:desktop",
  ":demo-app:desktop-glfw",
  ":demo-app:desktop-nucleus",
  ":lib",
  ":lib:maplibre-compose",
  ":lib:maplibre-compose-material3",
  ":lib:maplibre-compose-runtime-opengl-android",
  ":lib:maplibre-compose-runtime-vulkan-android",
  ":lib:location",
  ":lib:location-runtime-gms",
  ":lib:location-runtime-hms",
  ":lib:location-runtime-linux",
  ":lib:location-runtime-macos",
  ":lib:location-runtime-windows",
  ":lib:maplibre-compose-runtime-vulkan-linux-x64",
  ":lib:maplibre-compose-runtime-vulkan-linux-arm64",
  ":lib:maplibre-compose-runtime-opengl-linux-x64",
  ":lib:maplibre-compose-runtime-opengl-linux-arm64",
  ":lib:maplibre-compose-runtime-metal-macos-arm64",
  ":lib:maplibre-compose-runtime-vulkan-windows-x64",
  ":lib:maplibre-compose-runtime-vulkan-windows-arm64",
  ":lib:maplibre-compose-runtime-opengl-windows-x64",
  ":lib:maplibre-compose-runtime-opengl-windows-arm64",
)
