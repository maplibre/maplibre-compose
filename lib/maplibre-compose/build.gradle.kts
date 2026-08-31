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
    name = "MapLibre Compose"
    description = "Add interactive vector tile maps to your Compose app"
    url = "https://github.com/maplibre/maplibre-compose"
  }
}

kotlin {
  android {
    namespace = "org.maplibre.compose"
    optimization {
      // The Vulkan bridge class is resolved by JNI name.
      consumerKeepRules.publish = true
      consumerKeepRules.files.add(project.file("consumer-rules.pro"))
    }
  }

  iosArm64()
  iosSimulatorArm64()

  jvm { compilerOptions { jvmTarget = project.getDesktopJvmTarget() } }

  js {
    // The MapLibre GL JS declarations are @file:JsModule with no global to fall back on, which UMD
    // output rejects. Every consumer of this module's js target has to match.
    useEsModules()
    // Compose UI browser tests need an executable binary so webpack can load the Skiko runtime
    // (CMP-4906).
    binaries.executable()
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
      api(project(":lib:location"))
      implementation(libs.jetbrains.compose.foundation)
      implementation(libs.jetbrains.compose.components.resources)
      implementation(libs.htmlConverterCompose)
      api(libs.lifecycle.runtime.compose)
      api(libs.kermit)
      implementation(libs.kotlinx.coroutines.core)
      implementation(libs.kotlinx.atomicfu)
      api(libs.spatialk.geojson)
      api(libs.spatialk.units)
    }

    // Desktop, iOS, and the browser. Android implements the same expect APIs in androidMain,
    // because Compose on Android draws through the Android Canvas API instead of Skia.
    create("nonAndroidMain") {
      dependsOn(commonMain.get())
      jvmMain.dependsOn(this)
      iosMain.get().dependsOn(this)
      jsMain.get().dependsOn(this)
    }

    // MapLibre Native platforms (Android, iOS, desktop). The browser stays on MapLibre GL JS.
    // This source set stays free of java.* so a Native actual can sit beside the Java one.
    val maplibreNativeMain =
      create("maplibreNativeMain") {
        dependsOn(commonMain.get())
        iosMain.get().dependsOn(this)
        dependencies {
          // Backend-independent binding only; the application selects the native runtime.
          api(libs.maplibre.nativeFfi)
          // Multiplatform filesystem paths, so this source set stays free of java.io.File.
          implementation(libs.kotlinx.io.core)
        }
      }

    // Java implementations shared by Android and desktop, including mln-ffi actuals that iOS
    // provides separately in iosMain.
    create("androidJvmMain") {
      dependsOn(maplibreNativeMain)
      androidMain.get().dependsOn(this)
      jvmMain.dependsOn(this)
    }

    iosMain {
      dependencies {
        // iOS runs the Metal backend, on the device and in the simulator; the runtime klib
        // carries the static MapLibre Native archive and its Apple framework linker opts.
        implementation(libs.maplibre.nativeFfi.runtimeMetalKmp)
      }
    }

    jvmMain.apply {
      dependencies {
        implementation(compose.desktop.common)

        // The Compose host needs direct Vulkan/OpenGL access; the natives come from the runtime
        // artifact the application picks.
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
      implementation(libs.kotlinx.coroutines.test)

      implementation(libs.jetbrains.compose.ui.test)
    }

    // Live-map and Compose UI tests shared by jsTest and every platform that consumes
    // maplibreNativeMain. androidHostTest inherits commonTest and has no MapLibre runtime and no
    // Compose UI test host.
    val liveMapTest =
      create("liveMapTest") {
        dependsOn(commonTest.get())
        jsTest.get().dependsOn(this)
      }

    // Behavioral contracts for the shared MapLibre Native integration. Every platform that
    // consumes maplibreNativeMain must execute this source set except androidHostTest, which has
    // no MapLibre runtime. The platform test source supplies only runtime, render-host, storage,
    // and Compose-runner adapters.
    val maplibreNativeTest =
      create("maplibreNativeTest") {
        dependsOn(commonTest.get())
        dependsOn(liveMapTest)
      }

    // Java implementations of the shared test adapters. This source set also holds the handful of
    // shared-suite tests that only its runners can host: a test that composes the real map view
    // needs a Compose test runner that hosts a native interop view, which the iOS headless runner
    // cannot (LocalInteropContainer is internal to Compose UI), and one test that exercises JVM
    // thread interruption.
    create("androidJvmTest") {
      dependsOn(maplibreNativeTest)
      getByName("androidDeviceTest").dependsOn(this)
      getByName("jvmTest").dependsOn(this)
    }

    // iOS executes the same shared Native contract suite; its test source supplies the Native
    // platform adapters instead of the Java ones in androidJvmTest.
    getByName("iosTest").dependsOn(maplibreNativeTest)

    // One native runtime is loaded per test process; `maplibre.desktop.backend` selects which, and
    // a CI matrix adds processes for additional applicable backends.
    val jvmTest by getting
    jvmTest.dependencies {
      implementation(compose.desktop.currentOs)
      // Only the EGL interop tests bind EGL directly; nothing in the library does.
      implementation(libs.lwjgl.egl)
    }

    androidHostTest.dependencies { implementation(compose.desktop.currentOs) }

    androidDeviceTest.dependencies {
      implementation(libs.androidx.activity.compose)
      implementation(libs.jetbrains.compose.ui.testJunit4)
      implementation(libs.androidx.composeUi.testManifest)
      // The shared device-test render driver targets OpenGL.
      implementation(libs.maplibre.nativeFfi.runtimeOpenGl)
    }
  }
}

val requestedDesktopBackend = providers.gradleProperty("maplibre.desktop.backend")

configurations.named("jvmTestRuntimeOnly") {
  dependencies.addAllLater(
    providers.provider {
      val platform = DesktopHostPlatform.current()
      platform
        .runtimeDependencies(
          backend = platform.selectedRenderBackend(requestedDesktopBackend.orNull),
          ffiVersion = libs.versions.maplibre.nativeFfi.get(),
          lwjglVersion = libs.versions.lwjgl.get(),
        )
        .map(project.dependencies::create)
    }
  )
}

compose.resources { packageOfResClass = "org.maplibre.compose.generated" }

// Kotlin/Native spawns simulator tests with `simctl spawn --standalone`, whose bootstrap context
// has no access to the GPU daemon, so MTLCreateSystemDefaultDevice returns nil there. The map
// tests render through Metal, so they run in the regular bootstrap instead.
tasks.withType<org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest> {
  standalone.set(false)
}

// These classes open a shared cache, a second RuntimeHandle, or a process-wide
// logger. They cannot share the live jvmTest JVM: a second createCacheFile()
// interleaves schema creation with MlnFfiSharedCacheDatabaseTest
// (maplibre-native-ffi#667). jvmProcessGlobalTest reuses the KMP jvmTest
// classpath and forks a new JVM per class. jvmTest still runs them via
// dependsOn, so `mise run test:desktop` and `./gradlew jvmTest` keep the
// coverage.
val jvmProcessGlobalTestClasses =
  listOf(
    "org.maplibre.compose.offline.MlnFfiSharedCacheDatabaseTest",
    "org.maplibre.compose.offline.MlnFfiOfflinePackTest",
    "org.maplibre.compose.offline.MlnFfiOfflineManagerTest",
    "org.maplibre.compose.offline.MlnFfiOfflineRuntimeTest",
    "org.maplibre.compose.map.PlatformMapAccessTest",
    "org.maplibre.compose.desktop.MapLibreConfigurationTest",
    "org.maplibre.compose.sources.ImageSourceAttachTest",
    "org.maplibre.compose.layers.UnsupportedLayerPropertyTest",
  )

val jvmTestTask = tasks.named<Test>("jvmTest")

val jvmProcessGlobalTest =
  tasks.register<Test>("jvmProcessGlobalTest") {
    group = "verification"
    description =
      "Run process-global cache, dual-runtime, and logger tests " + "in a new JVM per class."
    val jvmTest = jvmTestTask.get()
    testClassesDirs = jvmTest.testClassesDirs
    classpath = jvmTest.classpath
    // KMP jvmTest uses kotlin-test JUnit 4, not JUnit Platform.
    useJUnit()
    forkEvery = 1
    filter {
      jvmProcessGlobalTestClasses.forEach { className ->
        includeTestsMatching(className)
        includeTestsMatching("$className.*")
      }
      isFailOnNoMatchingTests = false
    }
  }

// Layers 0–2 only. Used by `-Pmaplibre.tests=unit` / `mise run test:desktop-unit`.
// Explicit class names, not package prefixes: MlnFfiConversionsTest and
// MlnFfiMapPixelTest share a package. Do not allowlist PlatformMapAccessTest,
// MlnFfiOffline*, or MapLibreConfigurationTest — they open a runtime or cache.
val jvmUnitTestClasses =
  listOf(
    "org.maplibre.compose.layers.SymbolLayerCompositionTest",
    "org.maplibre.compose.layers.UnknownLayerJsonTest",
    "org.maplibre.compose.location.LocationPuckTest",
    "org.maplibre.compose.location.LocationStateTest",
    "org.maplibre.compose.map.GestureContinuationTest",
    "org.maplibre.compose.map.GestureMathTest",
    "org.maplibre.compose.map.MapExtentTest",
    "org.maplibre.compose.map.MapInputRecognitionTest",
    "org.maplibre.compose.map.MapLifecycleBindingTest",
    "org.maplibre.compose.map.MapLifecycleCallbackRaceTest",
    "org.maplibre.compose.map.MapPresentationTest",
    "org.maplibre.compose.map.MapRuntimeTest",
    "org.maplibre.compose.map.MlnFfiMapHostSessionRequestTest",
    "org.maplibre.compose.map.TapPairingTest",
    "org.maplibre.compose.mlnffi.FileUrlTest",
    "org.maplibre.compose.mlnffi.MlnFfiMapSurfaceRecoveryTest",
    "org.maplibre.compose.mlnffi.MlnFfiMapSurfaceReplacementTest",
    "org.maplibre.compose.mlnffi.MlnFfiOwnerThreadTest",
    "org.maplibre.compose.mlnffi.MlnFfiPathTest",
    "org.maplibre.compose.mlnffi.RenderBackendNegotiationTest",
    "org.maplibre.compose.offline.OfflineProgressMappingTest",
    "org.maplibre.compose.overlay.EllipseIntersectionTest",
    "org.maplibre.compose.overlay.MapOverlayTest",
    "org.maplibre.compose.overlay.MaplibreLogoTest",
    "org.maplibre.compose.resource.DesktopResourceReadTest",
    "org.maplibre.compose.resource.MlnFfiResourceProviderTest",
    "org.maplibre.compose.resource.MlnFfiResourceRequestTest",
    "org.maplibre.compose.sources.CustomSourceDefinitionTest",
    "org.maplibre.compose.sources.GeoJsonConflationTest",
    "org.maplibre.compose.sources.MlnFfiFeatureStateStoreTest",
    "org.maplibre.compose.sources.MlnFfiTileRequestCoordinatorTest",
    "org.maplibre.compose.sources.RasterDemSourceJsonTest",
    "org.maplibre.compose.sources.SourceJsonTest",
    "org.maplibre.compose.sources.TileCoordinateTest",
    "org.maplibre.compose.style.StyleCompositionEvaluatorTest",
    "org.maplibre.compose.style.StyleCompositionOrderTest",
    "org.maplibre.compose.style.StyleDefinitionAndIdentityTest",
    "org.maplibre.compose.style.StyleNodeTest",
    "org.maplibre.compose.style.StyleOwnershipTest",
    "org.maplibre.compose.testing.RecordingListTest",
    "org.maplibre.compose.util.AngleMathTest",
    "org.maplibre.compose.util.ExpressionJsonTest",
    "org.maplibre.compose.util.ExpressionSplitJoinTest",
    "org.maplibre.compose.util.ExpressionSwitchTest",
    "org.maplibre.compose.util.ImagePremultiplyTest",
    "org.maplibre.compose.util.ImageStretchResolveTest",
    "org.maplibre.compose.util.JsonConversionsTest",
    "org.maplibre.compose.util.MlnFfiConversionsTest",
    "org.maplibre.compose.util.NumberFormatterTest",
    "org.maplibre.compose.desktop.skiko.SkikoReflectionContractTest",
  )

val unitTestsOnly = providers.gradleProperty("maplibre.tests").orNull == "unit"

jvmTestTask.configure {
  if (unitTestsOnly) {
    filter {
      jvmUnitTestClasses.forEach { className ->
        includeTestsMatching(className)
        includeTestsMatching("$className.*")
      }
      isFailOnNoMatchingTests = true
    }
  } else {
    filter {
      jvmProcessGlobalTestClasses.forEach { className ->
        excludeTestsMatching(className)
        excludeTestsMatching("$className.*")
      }
      isFailOnNoMatchingTests = false
    }
    dependsOn(jvmProcessGlobalTest)
  }
}

if (unitTestsOnly) {
  jvmProcessGlobalTest.configure { enabled = false }
}

// `--tests` is stored on DefaultTestFilter, not on the public TestFilter type,
// and Gradle applies it only to Test tasks named on the command line. Copy it
// onto the isolated task so `jvmTest --tests FileUrlTest` does not run these
// classes. Skip the isolated task when the filter cannot select one of them.
gradle.taskGraph.whenReady {
  if (unitTestsOnly) return@whenReady
  val jvmTest = jvmTestTask.get()
  val isolated = jvmProcessGlobalTest.get()
  if (!hasTask(jvmTest) || !hasTask(isolated)) return@whenReady
  val requested =
    (jvmTest.filter as org.gradle.api.internal.tasks.testing.filter.DefaultTestFilter)
      .commandLineIncludePatterns
  if (requested.isEmpty()) return@whenReady
  val selected =
    requested
      .flatMap { pattern ->
        val prefix = pattern.trimEnd('*').removeSuffix(".")
        jvmProcessGlobalTestClasses.filter { className ->
          className == pattern ||
            className.startsWith(prefix) ||
            className.substringAfterLast('.') == pattern ||
            pattern.startsWith(className)
        }
      }
      .distinct()
  isolated.filter.setIncludePatterns(*selected.flatMap { listOf(it, "$it.*") }.toTypedArray())
  isolated.onlyIf { selected.isNotEmpty() }
}
