@file:OptIn(ExperimentalKotlinGradlePluginApi::class)

import org.jetbrains.compose.ExperimentalComposeLibrary
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSetTree

plugins {
  id("module-conventions")
  id(libs.plugins.kotlin.multiplatform.get().pluginId)
  id(libs.plugins.android.application.get().pluginId)
  id(libs.plugins.kotlin.composeCompiler.get().pluginId)
  id(libs.plugins.compose.get().pluginId)
  id(libs.plugins.kotlin.cocoapods.get().pluginId)
  id(libs.plugins.kotlin.serialization.get().pluginId)
}

android {
  namespace = "dev.sargunv.maplibrecompose.demoapp"

  defaultConfig {
    applicationId = "dev.sargunv.maplibrecompose.demoapp"
    minSdk = project.properties["androidMinSdk"]!!.toString().toInt()
    compileSdk = project.properties["androidCompileSdk"]!!.toString().toInt()
    targetSdk = project.properties["androidTargetSdk"]!!.toString().toInt()
    versionCode = 1
    versionName = project.version.toString()
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }
  packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
  buildTypes { getByName("release") { isMinifyEnabled = false } }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }

  @Suppress("UnstableApiUsage") testOptions { animationsDisabled = true }
}

kotlin {
  androidTarget {
    compilerOptions { jvmTarget = project.getJvmTarget() }
    instrumentedTestVariant.sourceSetTree.set(KotlinSourceSetTree.test)
  }
  iosArm64()
  iosSimulatorArm64()
  iosX64()
  jvm("desktop") { compilerOptions { jvmTarget = project.getJvmTarget() } }
  js(IR) {
    browser { commonWebpackConfig { outputFileName = "app.js" } }
    binaries.executable()
  }

  cocoapods {
    summary = "MapLibre Compose demo app"
    homepage = "https://github.com/maplibre/maplibre-compose"
    ios.deploymentTarget = "15.3" // TODO reduce this to same as library target?
    podfile = project.file("../iosApp/Podfile")
    framework {
      baseName = "DemoApp"
      version = "0.0.0" // not using real version here because it'll pollute the git diff
    }
    pod("MapLibre", libs.versions.maplibre.ios.get())
  }

  compilerOptions {
    allWarningsAsErrors = false // TODO re-enable after Compose 1.8.1
    freeCompilerArgs.addAll("-Xexpect-actual-classes", "-Xconsistent-data-class-copy-visibility")
  }

  sourceSets {
    val desktopMain by getting

    all { languageSettings { optIn("androidx.compose.material3.ExperimentalMaterial3Api") } }

    commonMain.dependencies {
      implementation(compose.components.resources)
      implementation(compose.foundation)
      implementation(compose.material3)
      implementation(compose.runtime)
      implementation(compose.ui)
      implementation(libs.lifecycle.runtime.compose)
      implementation(libs.androidx.navigation.compose)
      implementation(libs.ktor.client.core)
      implementation(libs.ktor.client.contentNegotiation)
      implementation(libs.ktor.serialization.kotlinxJson)
      implementation(project(":lib:maplibre-compose"))
      implementation(project(":lib:maplibre-compose-material3"))
    }

    androidMain.dependencies {
      implementation(libs.androidx.activity.compose)
      implementation(libs.kotlinx.coroutines.android)
      implementation(libs.ktor.client.okhttp)
    }

    iosMain.dependencies { implementation(libs.ktor.client.darwin) }

    desktopMain.dependencies {
      implementation(compose.desktop.currentOs)
      implementation(libs.kotlinx.coroutines.swing)
      implementation(libs.ktor.client.okhttp)
    }

    jsMain.dependencies {
      implementation(compose.html.core)
      implementation(libs.ktor.client.js)
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

compose.resources { packageOfResClass = "dev.sargunv.maplibrecompose.demoapp.generated" }

composeCompiler { reportsDestination = layout.buildDirectory.dir("compose/reports") }

compose.desktop {
  application {
    mainClass = "dev.sargunv.maplibrecompose.demoapp.MainKt"

    nativeDistributions {
      targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
      packageName = "dev.sargunv.maplibrecompose.demoapp"
      // https://youtrack.jetbrains.com/issue/CMP-2360
      // packageVersion = project.ext["base_tag"].toString().replace("v", "")
      packageVersion = "1.0.0"
    }

    // https://github.com/KevinnZou/compose-webview-multiplatform/blob/main/README.desktop.md#flags
    jvmArgs("--add-opens", "java.desktop/sun.awt=ALL-UNNAMED")
    jvmArgs(
      "--add-opens",
      "java.desktop/java.awt.peer=ALL-UNNAMED",
    ) // recommended but not necessary
    if (System.getProperty("os.name").contains("Mac")) {
      jvmArgs("--add-opens", "java.desktop/sun.lwawt=ALL-UNNAMED")
      jvmArgs("--add-opens", "java.desktop/sun.lwawt.macosx=ALL-UNNAMED")
    }
  }
}
