plugins {
  id("library-conventions")
  id("android-library-conventions")
  id(libs.plugins.kotlin.multiplatform.get().pluginId)
  id(libs.plugins.kotlin.composeCompiler.get().pluginId)
  id(libs.plugins.android.library.get().pluginId)
  id(libs.plugins.compose.get().pluginId)
  id(libs.plugins.mavenPublish.get().pluginId)
}

mavenPublishing {
  pom {
    name = "MapLibre Compose Location Google Play Services"
    description = "Google Play Services location and orientation providers for MapLibre Compose."
    url = "https://github.com/maplibre/maplibre-compose"
  }
}

kotlin {
  android {
    namespace = "org.maplibre.compose.gms"
    optimization {
      // Keeps the ServiceLoader-registered backend in consuming applications.
      consumerKeepRules.publish = true
      consumerKeepRules.files.add(project.file("consumer-rules.pro"))
    }
  }

  applyDefaultHierarchyTemplate()

  sourceSets {
    commonMain.dependencies { api(project(":lib:location")) }

    androidMain.dependencies {
      // Compose UI for LocalContext in the remember helpers, matching the location module.
      implementation(libs.jetbrains.compose.ui)
      implementation(libs.playServices.location)
      implementation(libs.kotlinx.coroutines.playServices)
    }

    commonTest.dependencies {
      implementation(kotlin("test"))
      implementation(kotlin("test-common"))
      implementation(kotlin("test-annotations-common"))
      implementation(libs.jetbrains.compose.ui.test)
    }

    androidHostTest.dependencies {
      implementation(compose.desktop.currentOs)
      implementation(libs.playServices.location)
      implementation(libs.kotlinx.coroutines.test)
    }

    androidDeviceTest.dependencies {
      implementation(libs.jetbrains.compose.ui.testJunit4)
      implementation(libs.androidx.composeUi.testManifest)
    }
  }
}
