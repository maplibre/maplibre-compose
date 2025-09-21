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
  id(libs.plugins.spmForKmp.get().pluginId)
}

mavenPublishing {
  pom {
    name = "MapLibre Compose"
    description = "Add interactive vector tile maps to your Compose app"
    url = "https://github.com/maplibre/maplibre-compose"
  }
}

kotlin {
  androidLibrary { namespace = "org.maplibre.compose" }

  listOf(iosArm64(), iosSimulatorArm64(), iosX64()).forEach {
    it.compilations.getByName("main") {
      cinterops {
        create("observer") {
          defFile(project.file("src/nativeInterop/cinterop/observer.def"))
          packageName("org.maplibre.compose.util")
        }
      }
    }
    it.configureSpmMaplibre(project)
  }

  jvm("desktop") { compilerOptions { jvmTarget = project.getJvmTarget() } }

  js(IR) { browser() }

  applyDefaultHierarchyTemplate()

  sourceSets {
    val desktopMain by getting

    listOf(iosMain, iosArm64Main, iosSimulatorArm64Main, iosX64Main).forEach {
      it { languageSettings { optIn("kotlinx.cinterop.ExperimentalForeignApi") } }
    }

    commonMain.dependencies {
      implementation(compose.foundation)
      implementation(compose.components.resources)
      api(libs.kermit)
      api(libs.spatialk.geojson)
    }

    // used to share some implementation on targets where Compose UI is backed by Skia directly
    // (e.g. all but Android, which is backed by the Android Canvas API)
    create("skiaMain") {
      dependsOn(commonMain.get())
      desktopMain.dependsOn(this)
      iosMain.get().dependsOn(this)
      jsMain.get().dependsOn(this)
    }

    // used to expose APIs only available on targets backed by MapLibre Native
    // (e.g. all but browser targets, which use MapLibre JS)
    create("maplibreNativeMain") {
      dependsOn(commonMain.get())
      androidMain.get().dependsOn(this)
      iosMain.get().dependsOn(this)
      // TODO: when we're ready to support the offline manager on desktop
      // desktopMain.dependsOn(this)
    }

    iosMain {}

    androidMain {
      dependencies {
        api(libs.maplibre.android)
        implementation(libs.maplibre.android.scalebar)
      }
    }

    desktopMain.apply {
      dependencies {
        implementation(compose.desktop.currentOs)
        implementation(libs.kotlinx.coroutines.swing)
        implementation(project(":lib:maplibre-native-bindings"))
      }
    }

    jsMain { dependencies { implementation(project(":lib:maplibre-js-bindings")) } }

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

compose.resources { packageOfResClass = "org.maplibre.compose.generated" }
