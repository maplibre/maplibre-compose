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

val desktopResources: Configuration by
  configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
  }

dependencies {
  desktopResources(
    project(path = ":lib:maplibre-compose-webview", configuration = "jsBrowserDistribution")
  )
}

val copyDesktopResources by
  tasks.registering(Copy::class) {
    from(desktopResources)
    eachFile { path = "files/${path}" }
    into(project.layout.buildDirectory.dir(desktopResources.name))
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

    // used to share some implementation on platforms where Compose UI is backed by Skia directly
    // (e.g. all but Android, which is backed by the Android Canvas API)
    val skiaMain by creating { dependsOn(commonMain.get()) }

    // used to expose APIs only available on platforms backed by MapLibre Native
    // (e.g. Android and iOS, and maybe someday Desktop)
    val maplibreNativeMain by creating { dependsOn(commonMain.get()) }

    // used to expose APIs only available on platforms backed by MapLibre JS
    // (e.g. JS, Desktop for now, and someday WASM)
    val maplibreJsMain by creating { dependsOn(commonMain.get()) }

    iosMain {
      dependsOn(skiaMain)
      dependsOn(maplibreNativeMain)
    }

    androidMain {
      dependsOn(maplibreNativeMain)
      dependencies {
        api(libs.maplibre.android)
        implementation(libs.maplibre.android.scalebar)
      }
    }

    desktopMain.apply {
      dependsOn(skiaMain)
      dependsOn(maplibreJsMain)
      dependencies {
        implementation(compose.desktop.currentOs)
        implementation(libs.kotlinx.coroutines.swing)
        implementation(libs.webview)
      }
    }

    jsMain {
      dependsOn(skiaMain)
      dependsOn(maplibreJsMain)
      dependencies {
        // TODO replace this with the official component in the next version of Compose
        implementation("dev.sargunv.maplibre-compose:compose-html-interop:0.10.4")
        implementation(project(":lib:kotlin-maplibre-js"))
      }
    }

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

compose.resources {
  packageOfResClass = "org.maplibre.compose.generated"

  customDirectory(
    sourceSetName = "desktopMain",
    directoryProvider = layout.dir(copyDesktopResources.map { it.destinationDir }),
  )
}
