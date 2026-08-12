plugins {
  id("module-conventions")
  id(libs.plugins.kotlin.jvm.get().pluginId)
  id(libs.plugins.kotlin.composeCompiler.get().pluginId)
  id(libs.plugins.compose.get().pluginId)
}

val desktopHostPlatform = DesktopHostPlatform.current()

kotlin {
  jvmToolchain(libs.versions.java.toolchain.get().toInt())
  compilerOptions { jvmTarget = project.getDesktopJvmTarget() }
}

dependencies {
  implementation(project(":demo-app:common"))
  implementation(project(":lib:maplibre-compose"))
  implementation(libs.composeGlfw)

  runtimeOnly(desktopHostPlatform.composeGlfwRuntimeDependency(libs.versions.composeGlfw.get()))
  runtimeOnly(project(":lib:${desktopHostPlatform.defaultRuntimeArtifactId}"))
}

/**
 * Runs the fixture. A plain `JavaExec` rather than the Compose Desktop application plugin, which
 * packages an AWT/Skiko application. Both JVM arguments are mandatory: GLFW must own AppKit's first
 * thread on macOS, and the FFI binding is refused native access without the other.
 *
 * This stays a module of its own so that its `MainDispatcherFactory` service, which outranks
 * `kotlinx-coroutines-swing`, never reaches the AWT demo's runtime classpath.
 */
val mainRuntimeClasspath = sourceSets.named("main").map { it.runtimeClasspath }

val run by
  tasks.registering(JavaExec::class) {
    group = "application"
    description = "Runs the compose-glfw desktop host fixture."

    classpath = mainRuntimeClasspath.get()
    mainClass = "org.maplibre.compose.glfw.MainKt"
    jvmArgs(NATIVE_ACCESS_JVM_ARGS)
    if (System.getProperty("os.name").lowercase().startsWith("mac")) {
      jvmArgs("-XstartOnFirstThread")
    }
    javaLauncher = javaToolchains.launcherFor {
      languageVersion = JavaLanguageVersion.of(libs.versions.java.toolchain.get().toInt())
    }
  }
