plugins {
  id("module-conventions")
  id(libs.plugins.kotlin.multiplatform.get().pluginId)
  id(libs.plugins.kotlin.composeCompiler.get().pluginId)
  id(libs.plugins.compose.get().pluginId)
}

val desktopHostPlatform = DesktopHostPlatform.current()

kotlin {
  jvmToolchain(properties["jvmToolchain"]!!.toString().toInt())

  jvm("desktop") { compilerOptions { jvmTarget = project.getDesktopJvmTarget() } }

  sourceSets {
    val desktopMain by getting

    desktopMain.dependencies {
      // The point of this module: it depends on the library, and the library does not depend on
      // it. Everything below the SPI line — compose-glfw, LWJGL, the Metal bridge — is the
      // fixture's, and `:lib:maplibre-compose` never sees any of it.
      implementation(project(":lib:maplibre-compose"))
      // The fixture runs the real demo app rather than a cut-down one, because "the same public
      // MaplibreMap composable works" is only worth asserting against the whole thing: every demo,
      // every gesture, the style switcher, and the offline screens all go through this host. The
      // dependency points this way so that `:demo-app` keeps knowing nothing about compose-glfw.
      implementation(project(":demo-app"))
      implementation(libs.composeGlfw)
      implementation(libs.jetbrains.compose.foundation)
      implementation(libs.jetbrains.compose.runtime)
      implementation(libs.jetbrains.compose.ui)
      // Objective-C messaging for the Metal bridge, the same way the default host does it.
      implementation(libs.lwjgl.core)

      // compose-glfw ships one runtime per operating system and Compose backend, carrying GLFW,
      // Skiko, and the LWJGL natives the host needs. It is a runtime concern exactly like the FFI
      // runtime below.
      runtimeOnly(desktopHostPlatform.composeGlfwRuntimeDependency(libs.versions.composeGlfw.get()))
      runtimeOnly(desktopHostPlatform.runtimeDependency(libs.versions.maplibre.nativeFfi.get()))

      // LWJGL resolves its natives from the classpath. compose-glfw's runtime brings its own set,
      // but this fixture calls `org.lwjgl.system.JNI` and `ObjCRuntime` itself, so it names the
      // core natives rather than relying on a transitive it does not control.
      val lwjglVersion = libs.versions.lwjgl.get()
      runtimeOnly("org.lwjgl:lwjgl:$lwjglVersion:${desktopHostPlatform.lwjglNativesClassifier}")
    }
  }
}

/**
 * Keeps AWT's coroutine main dispatcher off the fixture's classpath.
 *
 * This is the one line here that is not about graphics, and it is a finding rather than a
 * workaround of convenience. `androidx.lifecycle`, which the demo reaches through
 * navigation-compose, enforces that lifecycle observers are added on "the main thread", and on
 * desktop it decides which thread that is by running a block on `Dispatchers.Main` and remembering
 * the thread it landed on. `kotlinx-coroutines-swing` registers the AWT event thread as that
 * dispatcher, and compose-glfw registers nothing — it has a UI dispatcher of its own but keeps it
 * internal — so with both on the classpath every navigation transition throws `Method addObserver
 * must be called on the main thread` from the GLFW thread, before the first frame. Removed, the
 * check finds no main dispatcher at all and permits any thread.
 *
 * Two things pull the dependency in: `:demo-app` declares it, and so does `:lib:maplibre-compose`.
 * The library one is the part worth fixing — see `DesktopOfflineManager`, which posts state updates
 * to `Dispatchers.Main` and therefore assumes AWT in a module that otherwise takes its host through
 * an SPI. The excluded build is the honest way to find out what else does.
 */
configurations.named("desktopRuntimeClasspath") {
  exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-swing")
}

/**
 * Runs the fixture.
 *
 * A plain `JavaExec` rather than the Compose Desktop application plugin, because that plugin exists
 * to package an AWT/Skiko application and this fixture is the thing proving the map does not need
 * one. The two JVM arguments are both mandatory: GLFW must own AppKit's first thread on macOS, and
 * the MapLibre Native FFI binding is refused native access without the second.
 */
val runGlfwFixture by
  tasks.registering(JavaExec::class) {
    group = "application"
    description = "Runs the compose-glfw desktop host fixture."

    val compilation = kotlin.targets.getByName("desktop").compilations.getByName("main")
    dependsOn(compilation.compileTaskProvider)
    classpath(compilation.output.allOutputs, compilation.runtimeDependencyFiles)
    mainClass = "org.maplibre.compose.glfw.MainKt"
    jvmArgs(NATIVE_ACCESS_JVM_ARGS)
    if (System.getProperty("os.name").lowercase().startsWith("mac")) {
      jvmArgs("-XstartOnFirstThread")
    }
    javaLauncher = javaToolchains.launcherFor {
      languageVersion = JavaLanguageVersion.of(properties["jvmToolchain"]!!.toString().toInt())
    }
  }
