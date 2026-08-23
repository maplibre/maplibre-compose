plugins {
  id("module-conventions")
  id(libs.plugins.android.classicLibrary.get().pluginId)
  id(libs.plugins.mavenPublish.get().pluginId)
}

mavenPublishing {
  pom {
    name = "MapLibre Compose Runtime (Vulkan, Android)"
    description =
      "The MapLibre Native FFI Vulkan runtime and its Vulkan loader shim, " +
        "for MapLibre Compose on Android."
    url = "https://github.com/maplibre/maplibre-compose"
  }
}

android {
  namespace = "org.maplibre.compose.runtime.vulkan"

  compileSdk = libs.versions.android.compileSdk.get().toInt()

  // The revision .mise/bin/sync-android-packages pins, so every machine builds
  // the same shim rather than taking whatever NDK it happens to have.
  ndkVersion = "28.2.13676358"

  defaultConfig {
    minSdk = libs.versions.android.minSdk.get().toInt()

    ndk {
      // The FFI runtime packages these ABIs only.
      abiFilters += listOf("arm64-v8a", "x86_64")
    }
  }

  externalNativeBuild {
    cmake {
      path = file("src/main/cpp/CMakeLists.txt")
      version = "4.1.2"
    }
  }
}

dependencies {
  // api rather than runtimeOnly: the AAR's classes.jar is what carries the packaging.
  api(libs.maplibre.nativeFfi.runtimeVulkanKmp)
}
