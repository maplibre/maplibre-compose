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
    name = "MapLibre Compose Location"
    description = "Multiplatform location and orientation providers for Compose apps."
    url = "https://github.com/maplibre/maplibre-compose"
  }
}

kotlin {
  android { namespace = "org.maplibre.compose.location" }

  iosArm64()
  iosSimulatorArm64()

  jvm { compilerOptions { jvmTarget = project.getDesktopJvmTarget() } }

  js {
    // Matches the maplibre-compose module, whose MapLibre GL JS declarations reject UMD output.
    // Every consumer of that module's js target has to match, including this dependency.
    useEsModules()
    browser { testTask { useKarma { useChromeHeadless() } } }
  }

  applyDefaultHierarchyTemplate()

  sourceSets {
    listOf(iosMain, iosArm64Main, iosSimulatorArm64Main).forEach {
      it { languageSettings { optIn("kotlinx.cinterop.ExperimentalForeignApi") } }
    }

    commonMain.dependencies {
      api(libs.jetbrains.compose.runtime)
      api(libs.kotlinx.coroutines.core)
      api(libs.spatialk.geojson)
      api(libs.spatialk.units)
      implementation(libs.lifecycle.runtime.compose)
    }

    // Compose UI appears on Android only, for LocalContext in the remember helpers.
    androidMain.dependencies {
      implementation(libs.androidx.activity)
      implementation(libs.jetbrains.compose.ui)
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
      implementation(libs.jetbrains.compose.ui.test)
    }

    val jvmTest by getting
    jvmTest.dependencies { implementation(compose.desktop.currentOs) }
  }
}
