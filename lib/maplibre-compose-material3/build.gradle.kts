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
    name = "MapLibre Compose Material 3"
    description = "Material 3 extensions for MapLibre Compose."
    url = "https://github.com/maplibre/maplibre-compose"
  }
}

kotlin {
  android { namespace = "org.maplibre.compose.material3" }

  iosArm64()
  iosSimulatorArm64()

  jvm { compilerOptions { jvmTarget = project.getDesktopJvmTarget() } }

  js {
    // The core module's @file:JsModule MapLibre GL JS declarations require ES modules; UMD
    // rejects them. Match every other JS consumer of :lib:maplibre-compose.
    useEsModules()
    // Compose UI browser tests need an executable binary so webpack can load the Skiko runtime
    // (CMP-4906).
    binaries.executable()
    browser()
  }

  applyDefaultHierarchyTemplate()

  sourceSets {
    commonMain.dependencies {
      implementation(libs.jetbrains.compose.material3)
      implementation(libs.jetbrains.compose.components.resources)
      implementation(libs.bytesize)
      api(project(":lib:maplibre-compose"))
    }

    val maplibreNativeMain by creating { dependsOn(commonMain.get()) }

    iosMain { dependsOn(maplibreNativeMain) }

    androidMain { dependsOn(maplibreNativeMain) }

    // Desktop is backed by MapLibre Native, so it gets the offline controls.
    val jvmMain by getting
    jvmMain.dependsOn(maplibreNativeMain)

    jsMain { dependencies { implementation(libs.kotlin.wrappers.js) } }

    commonTest.dependencies {
      implementation(kotlin("test"))
      implementation(kotlin("test-common"))
      implementation(kotlin("test-annotations-common"))
      implementation(libs.jetbrains.compose.ui.test)
    }

    jvmTest.dependencies { implementation(compose.desktop.currentOs) }

    androidHostTest.dependencies { implementation(compose.desktop.currentOs) }

    androidDeviceTest.dependencies {
      implementation(libs.jetbrains.compose.ui.testJunit4)
      implementation(libs.androidx.composeUi.testManifest)
    }
  }
}

compose.resources { packageOfResClass = "org.maplibre.compose.material3.generated" }
