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
  android { namespace = "org.maplibre.compose" }

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

  jvm { compilerOptions { jvmTarget = project.getDesktopJvmTarget() } }

  js {
    // The MapLibre GL JS declarations are @file:JsModule with no global to fall back on, which UMD
    // output rejects. Every consumer of this module's js target has to match.
    useEsModules()
    // The browser platform composites MapLibre GL JS into the Compose scene, so its tests need a
    // real WebGL context; karma.config.d supplies the flags that give one to a headless browser.
    browser { testTask { useKarma { useChromeHeadless() } } }
  }

  applyDefaultHierarchyTemplate()

  sourceSets {
    val jvmMain by getting

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
      jvmMain.dependsOn(this)
      iosMain.get().dependsOn(this)
      jsMain.get().dependsOn(this)
    }

    // used to expose APIs only available on targets backed by MapLibre Native
    // (e.g. all but browser targets, which use MapLibre JS)
    val maplibreNativeMain =
      create("maplibreNativeMain") {
        dependsOn(commonMain.get())
        androidMain.get().dependsOn(this)
        iosMain.get().dependsOn(this)
      }

    // commonMain in waiting: the parts of the mln-ffi and MapLibre GL JS platforms that carry no
    // backend-conditional logic. It exists only because Android and iOS have typed layer actuals
    // rather than JSON-shaped ones; when those move onto mlnFfiShared, this merges into commonMain.
    val nextCommonMain =
      create("nextCommonMain") {
        dependsOn(commonMain.get())
        jsMain.get().dependsOn(this)
      }

    // used to share the integration with the MapLibre Native FFI binding, as opposed to the
    // MapLibre Android and iOS SDKs. Desktop is its only target today.
    create("mlnFfiShared") {
      dependsOn(maplibreNativeMain)
      dependsOn(nextCommonMain)
      jvmMain.dependsOn(this)
      dependencies {
        // Backend-independent binding only; the application selects the native runtime.
        implementation(libs.maplibre.nativeFfi)
        // Multiplatform filesystem paths, so this source set stays free of java.io.File.
        implementation(libs.kotlinx.io.core)
      }
    }

    iosMain {}

    androidMain {
      dependencies {
        api(libs.maplibre.android)
        implementation(libs.maplibre.android.scalebar)
      }
    }

    jvmMain.apply {
      dependencies {
        implementation(compose.desktop.currentOs)

        // The AWT Compose host needs direct Vulkan/OpenGL access; the natives come from the
        // runtime artifact the application picks.
        implementation(libs.lwjgl.core)
        implementation(libs.lwjgl.opengl)
        implementation(libs.lwjgl.vulkan)
      }
    }

    jsMain.dependencies {
      implementation(libs.kotlin.wrappers.js)
      implementation(libs.kotlin.wrappers.browser)
      implementation(npm("maplibre-gl", libs.versions.maplibre.js.get()))
    }

    commonTest.dependencies {
      implementation(kotlin("test"))
      implementation(kotlin("test-common"))
      implementation(kotlin("test-annotations-common"))

      implementation(libs.jetbrains.compose.ui.test)
    }

    // The test counterpart of nextCommonMain, and on the same path into commonTest.
    val nextCommonTest =
      create("nextCommonTest") {
        dependsOn(commonTest.get())
        jsTest.get().dependsOn(this)
      }

    // Behavioral contracts for the shared MapLibre Native FFI integration. Every platform that
    // consumes mlnFfiShared must execute this source set; the platform test source supplies only
    // runtime, render-host, storage, and Compose-runner adapters.
    create("mlnFfiSharedTest") {
      dependsOn(commonTest.get())
      dependsOn(nextCommonTest)
      getByName("jvmTest").dependsOn(this)
    }

    // Runtime dependencies belong to platform/backend adapters. One native runtime is loaded per
    // test process; a CI matrix adds processes for additional applicable backends.
    val jvmTest by getting
    jvmTest.dependencies {
      // Only the EGL interop test binds EGL directly; nothing in the library does.
      implementation(libs.lwjgl.egl)
    }

    androidHostTest.dependencies { implementation(compose.desktop.currentOs) }

    androidDeviceTest.dependencies {
      implementation(libs.jetbrains.compose.ui.testJunit4)
      implementation(libs.androidx.composeUi.testManifest)
    }
  }
}

configurations.named("jvmTestRuntimeOnly") {
  dependencies.addAllLater(
    providers.provider {
      val platform = DesktopHostPlatform.current()
      platform
        .runtimeDependencies(
          backend = platform.defaultRenderBackend,
          ffiVersion = libs.versions.maplibre.nativeFfi.get(),
          lwjglVersion = libs.versions.lwjgl.get(),
        )
        .map(project.dependencies::create)
    }
  )
}

compose.resources { packageOfResClass = "org.maplibre.compose.generated" }
