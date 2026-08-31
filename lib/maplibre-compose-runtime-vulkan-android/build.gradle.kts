import com.android.build.gradle.internal.cxx.configure.gradleLocalProperties
import com.vanniktech.maven.publish.AndroidSingleVariantLibrary

plugins {
  id("module-conventions")
  id(libs.plugins.android.classicLibrary.get().pluginId)
  id(libs.plugins.mavenPublish.get().pluginId)
}

mavenPublishing {
  // This runtime-only AAR has no API sources for AGP's embedded Dokka to document.
  configure(AndroidSingleVariantLibrary())
  pom {
    name = "MapLibre Compose Runtime (Vulkan, Android)"
    description =
      "The MapLibre Native FFI Vulkan runtime and its Vulkan loader shim, " +
        "for MapLibre Compose on Android."
    url = "https://github.com/maplibre/maplibre-compose"
  }
}

// externalNativeBuild needs the SDK at configuration time; without an SDK the
// module configures but does not build.
val hasAndroidSdk =
  gradleLocalProperties(rootDir, providers).getProperty("sdk.dir") != null ||
    providers.environmentVariable("ANDROID_HOME").isPresent ||
    providers.environmentVariable("ANDROID_SDK_ROOT").isPresent

android {
  namespace = "org.maplibre.compose.runtime.vulkan"

  compileSdk = libs.versions.android.compileSdk.get().toInt()

  ndkVersion = libs.versions.android.ndk.get()

  if (hasAndroidSdk) {
    externalNativeBuild {
      cmake {
        path = file("src/main/cpp/CMakeLists.txt")
        version = libs.versions.android.cmake.get()
      }
    }
  }

  defaultConfig {
    minSdk = libs.versions.android.minSdk.get().toInt()
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    ndk {
      // The FFI runtime packages these ABIs only.
      abiFilters += listOf("arm64-v8a", "x86_64")
    }
  }
}

dependencies {
  runtimeOnly(libs.maplibre.nativeFfi.runtimeVulkanKmp)

  androidTestImplementation(project(":lib:maplibre-compose"))
  androidTestImplementation(kotlin("test-junit"))
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.jetbrains.compose.ui)
  androidTestImplementation(libs.kotlinx.coroutines.core)
}
