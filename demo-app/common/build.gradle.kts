plugins {
  id("module-conventions")
  id("android-library-conventions")
  id(libs.plugins.kotlin.multiplatform.get().pluginId)
  id(libs.plugins.kotlin.serialization.get().pluginId)
  id(libs.plugins.android.library.get().pluginId)
  id(libs.plugins.kotlin.composeCompiler.get().pluginId)
  id(libs.plugins.compose.get().pluginId)
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
  }

  jvm { compilerOptions { jvmTarget = project.getDesktopJvmTarget() } }

  js {
    // maplibre-compose's MapLibre GL JS declarations are @file:JsModule with no global to fall
    // back to, which UMD output rejects; every consumer down the chain has to match.
    useEsModules()
    browser { commonWebpackConfig { outputFileName = "app.js" } }
    binaries.executable()
  }

  applyDefaultHierarchyTemplate()

  compilerOptions {
    freeCompilerArgs.addAll("-Xexpect-actual-classes", "-Xconsistent-data-class-copy-visibility")
  }

  sourceSets {
    all { languageSettings { optIn("androidx.compose.material3.ExperimentalMaterial3Api") } }

    // MapLibre Native platforms (Android, iOS, desktop). The browser stays on MapLibre GL JS,
    // so render toggles and offline UI live here.
    val maplibreNativeMain by creating {
      dependsOn(commonMain.get())
    }
    val androidJvmMain by creating { dependsOn(maplibreNativeMain) }
    val nonAndroidMain by creating { dependsOn(commonMain.get()) }

    androidMain { dependsOn(androidJvmMain) }

    jvmMain {
      dependsOn(androidJvmMain)
      dependsOn(nonAndroidMain)
    }

    iosMain {
      dependsOn(maplibreNativeMain)
      dependsOn(nonAndroidMain)
    }

    jsMain { dependsOn(nonAndroidMain) }

    commonMain.dependencies {
      // The platform modules compose against these, so they are api rather than implementation.
      api(libs.jetbrains.compose.foundation)
      api(libs.jetbrains.compose.runtime)
      api(libs.jetbrains.compose.ui)

      implementation(libs.jetbrains.compose.components.resources)
      implementation(libs.jetbrains.compose.material3)
      implementation(libs.jetbrains.compose.material3.adaptive)
      implementation(libs.materialKolor)
      implementation(libs.androidx.navigation.compose)
      implementation(libs.kotlin.dsv)
      implementation(libs.kotlinx.serialization.json)
      implementation(libs.ktor.client.core)
      implementation(libs.mobilityData.gtfsSchedule)
      implementation(libs.spatialk.geojson)

      api(project(":lib:maplibre-compose"))
      implementation(project(":lib:maplibre-compose-material3"))
    }

    // ktor-server only ships JVM and Android artifacts, so the agent driver server lives in this
    // source set; iOS and the browser get no-op actuals.
    androidJvmMain.dependencies {
      implementation(libs.ktor.server.cio)
      implementation(libs.ktor.server.statusPages)
    }

    androidMain {
      dependencies {
        implementation(libs.jetbrains.compose.ui.tooling)
        implementation(libs.androidx.activity.compose)
        implementation(libs.kotlinx.coroutines.android)
        implementation(libs.ktor.client.okhttp)
        implementation(project(":lib:location-runtime-gms"))
        implementation(project(":lib:location-runtime-hms"))
      }
    }

    jvmMain.dependencies {
      implementation(compose.desktop.currentOs)
      implementation(libs.kotlinx.coroutines.swing)
      implementation(libs.ktor.client.okhttp)
    }

    iosMain.dependencies { implementation(libs.ktor.client.darwin) }

    jsMain.dependencies {
      implementation(libs.jetbrains.compose.html.core)
      implementation(libs.kotlin.wrappers.js)
      implementation(libs.ktor.client.js)
      implementation(npm("fflate", libs.versions.fflate.get()))
      // IANA zone rules for kotlinx-datetime TimeZone.of on Kotlin/JS.
      implementation(npm("@js-joda/timezone", libs.versions.jsJodaTimezone.get()))
    }

    commonTest.dependencies { implementation(kotlin("test")) }

    // commonTest is on the device test classpath, so the APK must include the
    // runner android-library-conventions names in the instrumentation manifest.
    androidDeviceTest.dependencies {
      implementation(libs.jetbrains.compose.ui.testJunit4)
      implementation(libs.androidx.composeUi.testManifest)
    }
  }
}

compose.resources { packageOfResClass = "org.maplibre.compose.demoapp.generated" }

composeCompiler { reportsDestination = layout.buildDirectory.dir("compose/reports") }
