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
      implementation(project(":lib:maplibre-compose"))
      implementation(project(":demo-app"))
      implementation(libs.composeGlfw)
      implementation(libs.jetbrains.compose.foundation)
      implementation(libs.jetbrains.compose.runtime)
      implementation(libs.jetbrains.compose.ui)
      // Objective-C messaging for the Metal bridge, the same way the default host does it.
      implementation(libs.lwjgl.core)

      runtimeOnly(desktopHostPlatform.composeGlfwRuntimeDependency(libs.versions.composeGlfw.get()))
      runtimeOnly(desktopHostPlatform.runtimeDependency(libs.versions.maplibre.nativeFfi.get()))

      // LWJGL resolves its natives from the classpath, and this fixture calls `JNI`/`ObjCRuntime`
      // itself, so it names the core natives rather than relying on compose-glfw's transitive set.
      val lwjglVersion = libs.versions.lwjgl.get()
      runtimeOnly("org.lwjgl:lwjgl:$lwjglVersion:${desktopHostPlatform.lwjglNativesClassifier}")
    }
  }
}

/**
 * Runs the fixture. A plain `JavaExec` rather than the Compose Desktop application plugin, which
 * packages an AWT/Skiko application. Both JVM arguments are mandatory: GLFW must own AppKit's first
 * thread on macOS, and the FFI binding is refused native access without the other.
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
