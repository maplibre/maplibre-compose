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
  // The runtime artifacts name the same libmaplibre-native-c.so, so an application packages
  // exactly one of them; the map picks the host the packaged runtime renders with.
  api(libs.maplibre.nativeFfi.runtimeVulkanKmp)
}
