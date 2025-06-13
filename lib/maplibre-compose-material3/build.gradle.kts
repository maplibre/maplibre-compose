@file:OptIn(ExperimentalKotlinGradlePluginApi::class, ExperimentalComposeLibrary::class)

import org.jetbrains.compose.ExperimentalComposeLibrary
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSetTree

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

android { namespace = "dev.sargunv.maplibrecompose.material3" }

mavenPublishing {
  pom {
    name = "MapLibre Compose Material 3"
    description = "Material 3 extensions for MapLibre Compose."
    url = "https://github.com/maplibre/maplibre-compose"
  }
}

kotlin {
  androidTarget {
    compilerOptions { jvmTarget = project.getJvmTarget() }
    instrumentedTestVariant.sourceSetTree.set(KotlinSourceSetTree.test)
    publishLibraryVariants("release", "debug")
  }

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
      api(project(":lib:maplibre-compose"))
    }

    val maplibreNativeMain by creating { dependsOn(commonMain.get()) }

    val webMain by creating { dependsOn(commonMain.get()) }

    val nonWebMain by creating {
      dependsOn(commonMain.get())
      dependencies { implementation(libs.htmlConverterCompose) }
    }

    iosMain {
      dependsOn(maplibreNativeMain)
      dependsOn(nonWebMain)
    }

    androidMain {
      dependsOn(maplibreNativeMain)
      dependsOn(nonWebMain)
    }

    get("desktopMain").apply { dependsOn(nonWebMain) }

    jsMain {
      dependsOn(webMain)
      dependencies { implementation(libs.kotlin.wrappers.js) }
    }

    commonTest.dependencies {
      implementation(kotlin("test"))
      implementation(kotlin("test-common"))
      implementation(kotlin("test-annotations-common"))
      @OptIn(ExperimentalComposeLibrary::class) implementation(compose.uiTest)
    }

    androidUnitTest.dependencies { implementation(compose.desktop.currentOs) }

    androidInstrumentedTest.dependencies {
      implementation(compose.desktop.uiTestJUnit4)
      implementation(libs.androidx.composeUi.testManifest)
    }
  }
}

compose.resources { packageOfResClass = "dev.sargunv.maplibrecompose.material3.generated" }
