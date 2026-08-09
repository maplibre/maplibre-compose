plugins {
  id("module-conventions")
  id("android-library-conventions")
  id(libs.plugins.kotlin.multiplatform.get().pluginId)
  id(libs.plugins.android.library.get().pluginId)
  id(libs.plugins.kotlin.composeCompiler.get().pluginId)
  id(libs.plugins.compose.get().pluginId)
  id(libs.plugins.kotlin.serialization.get().pluginId)
  id(libs.plugins.spmForKmp.get().pluginId)
}

kotlin {
  jvmToolchain(libs.versions.java.toolchain.get().toInt())

  // Distinct from the app module's namespace, which AGP requires to be unique across modules.
  android { namespace = "org.maplibre.compose.demoapp.common" }

  listOf(iosArm64(), iosSimulatorArm64()).forEach {
    it.binaries.framework {
      baseName = "DemoApp"
      isStatic = true
    }
    it.configureSpmMaplibre(project)
  }

  jvm { compilerOptions { jvmTarget = project.getDesktopJvmTarget() } }

  js {
    browser { commonWebpackConfig { outputFileName = "app.js" } }
    binaries.executable()
  }

  applyDefaultHierarchyTemplate()

  compilerOptions {
    freeCompilerArgs.addAll("-Xexpect-actual-classes", "-Xconsistent-data-class-copy-visibility")
  }

  sourceSets {
    val jvmMain by getting

    all { languageSettings { optIn("androidx.compose.material3.ExperimentalMaterial3Api") } }

    commonMain.dependencies {
      // The platform modules compose against these, so they are api rather than implementation.
      api(libs.jetbrains.compose.foundation)
      api(libs.jetbrains.compose.runtime)
      api(libs.jetbrains.compose.ui)

      implementation(libs.jetbrains.compose.components.resources)
      implementation(libs.jetbrains.compose.material3)
      implementation(libs.androidx.navigation.compose)
      implementation(libs.ktor.client.core)
      implementation(libs.ktor.client.contentNegotiation)
      implementation(libs.ktor.serialization.kotlinxJson)
      implementation(libs.spatialk.geojson)

      api(project(":lib:maplibre-compose"))
      implementation(project(":lib:maplibre-compose-material3"))
    }

    val nonAndroidShared by creating { dependsOn(commonMain.get()) }

    val androidIosShared by creating { dependsOn(commonMain.get()) }

    // Platforms backed by MapLibre Native, where the offline API exists; mirrors the library's own
    // maplibreNativeMain source set.
    val maplibreNativeShared by creating { dependsOn(commonMain.get()) }

    // Android and Desktop use the shared MapLibre Native FFI implementation.
    val mlnFfiShared by creating { dependsOn(maplibreNativeShared) }

    val desktopJsShared by creating { dependsOn(commonMain.get()) }

    androidMain {
      dependsOn(androidIosShared)
      dependsOn(mlnFfiShared)
      dependencies {
        implementation(libs.jetbrains.compose.ui.tooling)
        implementation(libs.androidx.activity.compose)
        implementation(libs.kotlinx.coroutines.android)
        implementation(libs.ktor.client.okhttp)
        implementation(libs.accompanist.permissions)

        implementation(project(":lib:maplibre-compose-gms"))
      }
    }

    iosMain {
      dependsOn(androidIosShared)
      dependsOn(maplibreNativeShared)
      dependsOn(nonAndroidShared)
      dependencies { implementation(libs.ktor.client.darwin) }
    }

    jvmMain.apply {
      dependsOn(mlnFfiShared)
      dependsOn(nonAndroidShared)
      dependsOn(desktopJsShared)
      dependencies {
        implementation(compose.desktop.currentOs)
        implementation(libs.kotlinx.coroutines.swing)
        implementation(libs.ktor.client.okhttp)
      }
    }

    jsMain {
      dependsOn(nonAndroidShared)
      dependsOn(desktopJsShared)
      dependencies {
        implementation(libs.jetbrains.compose.html.core)
        implementation(libs.ktor.client.js)
      }
    }
  }
}

compose.resources { packageOfResClass = "org.maplibre.compose.demoapp.generated" }

composeCompiler { reportsDestination = layout.buildDirectory.dir("compose/reports") }
