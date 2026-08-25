plugins {
  id("library-conventions")
  id("android-library-conventions")
  id(libs.plugins.kotlin.multiplatform.get().pluginId)
  id(libs.plugins.android.library.get().pluginId)
  id(libs.plugins.mavenPublish.get().pluginId)
}

mavenPublishing {
  pom {
    name = "MapLibre Compose Location"
    description = "Multiplatform location and orientation providers, usable without Compose."
    url = "https://github.com/maplibre/maplibre-compose"
  }
}

kotlin {
  android { namespace = "org.maplibre.compose.location" }

  iosArm64()
  iosSimulatorArm64()

  jvm { compilerOptions { jvmTarget = project.getDesktopJvmTarget() } }

  js {
    useEsModules()
    browser { testTask { useKarma { useChromeHeadless() } } }
  }

  applyDefaultHierarchyTemplate()

  sourceSets {
    listOf(iosMain, iosArm64Main, iosSimulatorArm64Main).forEach {
      it { languageSettings { optIn("kotlinx.cinterop.ExperimentalForeignApi") } }
    }

    commonMain.dependencies {
      api(libs.kotlinx.coroutines.core)
      api(libs.spatialk.geojson)
      api(libs.spatialk.units)
    }

    androidMain.dependencies {
      implementation(libs.androidx.activity)
      implementation(libs.lifecycle.runtime)
    }

    jsMain.dependencies {
      implementation(libs.kotlin.wrappers.js)
      implementation(libs.kotlin.wrappers.browser)
    }

    commonTest.dependencies {
      implementation(kotlin("test"))
      implementation(kotlin("test-common"))
      implementation(kotlin("test-annotations-common"))
      implementation(libs.kotlinx.coroutines.test)
    }

    // The device test APK must package the instrumentation runner itself.
    androidDeviceTest.dependencies { implementation(libs.androidx.test.runner) }
  }
}
