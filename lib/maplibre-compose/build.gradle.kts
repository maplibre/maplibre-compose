plugins {
  id("library-conventions")
  id("android-library-conventions")
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

  listOf(iosArm64(), iosSimulatorArm64()).forEach {
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

  jvm("desktop") { compilerOptions { jvmTarget = project.getDesktopJvmTarget() } }

  js(IR) { browser() }

  applyDefaultHierarchyTemplate()

  sourceSets {
    val desktopMain by getting

    listOf(iosMain, iosArm64Main, iosSimulatorArm64Main).forEach {
      it { languageSettings { optIn("kotlinx.cinterop.ExperimentalForeignApi") } }
    }

    commonMain.dependencies {
      implementation(libs.jetbrains.compose.foundation)
      implementation(libs.jetbrains.compose.components.resources)
      implementation(libs.lifecycle.runtime.compose)
      api(libs.kermit)
      api(libs.spatialk.geojson)
      api(libs.spatialk.units)
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
      desktopMain.dependsOn(this)
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
        // Backend-independent binding only; the application selects the native runtime.
        implementation(libs.maplibre.nativeFfi)

        // The default Skiko host needs direct Vulkan/OpenGL access; the natives are the app's
        // concern.
        implementation(libs.lwjgl.core)
        implementation(libs.lwjgl.egl)
        implementation(libs.lwjgl.opengl)
        implementation(libs.lwjgl.vulkan)
      }
    }

    jsMain { dependencies { implementation(project(":lib:maplibre-js-bindings")) } }

    commonTest.dependencies {
      implementation(kotlin("test"))
      implementation(kotlin("test-common"))
      implementation(kotlin("test-annotations-common"))

      implementation(libs.jetbrains.compose.ui.test)
    }

    // Tests that reach the FFI need a native runtime, and always the Vulkan one: the headless test
    // host has no Metal equivalent.
    val desktopTest by getting
    desktopTest.dependencies {
      val platform = DesktopHostPlatform.current()
      runtimeOnly(platform.testRuntimeDependency(libs.versions.maplibre.nativeFfi.get()))

      // Core only: LWJGL loads Vulkan itself from the system loader, which on macOS comes from
      // `mise run bootstrap`. Without it the GPU-backed tests skip rather than fail.
      val lwjglVersion = libs.versions.lwjgl.get()
      runtimeOnly("org.lwjgl:lwjgl:$lwjglVersion:${platform.lwjglNativesClassifier}")
    }

    androidHostTest.dependencies { implementation(compose.desktop.currentOs) }

    androidDeviceTest.dependencies {
      implementation(libs.jetbrains.compose.ui.testJunit4)
      implementation(libs.androidx.composeUi.testManifest)
    }
  }
}

compose.resources { packageOfResClass = "org.maplibre.compose.generated" }
