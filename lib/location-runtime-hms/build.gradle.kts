plugins {
  id("library-conventions")
  id("android-library-conventions")
  id(libs.plugins.kotlin.multiplatform.get().pluginId)
  id(libs.plugins.android.library.get().pluginId)
  id(libs.plugins.mavenPublish.get().pluginId)
}

mavenPublishing {
  pom {
    name = "MapLibre Compose Location Huawei Mobile Services"
    description = "Huawei Mobile Services location provider for MapLibre Compose."
    url = "https://github.com/maplibre/maplibre-compose"
  }
}

kotlin {
  android {
    namespace = "org.maplibre.compose.hms"
    optimization {
      // Keeps the ServiceLoader-registered backend in consuming applications.
      consumerKeepRules.publish = true
      consumerKeepRules.files.add(project.file("consumer-rules.pro"))
    }
  }

  applyDefaultHierarchyTemplate()

  sourceSets {
    commonMain.dependencies { api(project(":lib:location")) }

    androidMain.dependencies { api(libs.hms.location) }

    // The device test APK must package the instrumentation runner itself.
    androidDeviceTest.dependencies { implementation(libs.androidx.test.runner) }

    androidHostTest.dependencies {
      implementation(kotlin("test"))
      implementation(libs.hms.location)
    }
  }
}
