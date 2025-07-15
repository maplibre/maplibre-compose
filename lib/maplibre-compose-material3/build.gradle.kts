import org.jetbrains.compose.ExperimentalComposeLibrary

plugins {
  id("library-conventions")
  id("android-library-conventions")
  id("spm-maplibre")
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
  androidLibrary { namespace = "org.maplibre.compose.material3" }

  listOf(iosArm64(), iosSimulatorArm64(), iosX64()).forEach { it.configureSpmMaplibre(project) }

  jvm("desktop") { compilerOptions { jvmTarget = project.getJvmTarget() } }

  js(IR) { browser() }

  applyDefaultHierarchyTemplate()

  sourceSets {
    commonMain.dependencies {
      api(libs.alchemist)
      implementation(compose.material3)
      implementation(compose.components.resources)
      implementation(libs.bytesize)
      implementation(libs.htmlConverterCompose)
      api(project(":lib:maplibre-compose"))
    }

    val maplibreNativeMain by creating { dependsOn(commonMain.get()) }

    iosMain { dependsOn(maplibreNativeMain) }

    androidMain { dependsOn(maplibreNativeMain) }

    jsMain { dependencies { implementation(libs.kotlin.wrappers.js) } }

    commonTest.dependencies {
      implementation(kotlin("test"))
      implementation(kotlin("test-common"))
      implementation(kotlin("test-annotations-common"))
      @OptIn(ExperimentalComposeLibrary::class) implementation(compose.uiTest)
    }

    androidHostTest.dependencies { implementation(compose.desktop.currentOs) }

    androidDeviceTest.dependencies {
      implementation(compose.desktop.uiTestJUnit4)
      implementation(libs.androidx.composeUi.testManifest)
    }
  }
}

compose.resources { packageOfResClass = "org.maplibre.compose.material3.generated" }
