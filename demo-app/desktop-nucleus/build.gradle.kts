plugins {
  id("module-conventions")
  id(libs.plugins.kotlin.jvm.get().pluginId)
  id(libs.plugins.kotlin.composeCompiler.get().pluginId)
  id(libs.plugins.compose.get().pluginId)
}

val desktopHostPlatform = DesktopHostPlatform.current()
val desktopRenderBackend =
  desktopHostPlatform.selectedRenderBackend(
    providers.gradleProperty("maplibre.desktop.backend").orNull
  )

kotlin {
  jvmToolchain(libs.versions.java.toolchain.get().toInt())
  compilerOptions { jvmTarget = project.getDesktopJvmTarget() }
}

dependencies {
  implementation(project(":demo-app:common"))
  implementation(project(":lib:maplibre-compose"))
  implementation(libs.nucleus.application)
  implementation(libs.nucleus.decoratedWindowTao)

  runtimeOnly(project(":lib:${desktopHostPlatform.runtimeArtifactId(desktopRenderBackend)}"))
}

/**
 * Runs the fixture. A plain `JavaExec` rather than the Nucleus application plugin: this module only
 * needs a runnable host for the shared demo, and packaging stays with the AWT desktop app.
 *
 * Both JVM arguments are mandatory on the hosts that need them: Tao must own AppKit's first thread
 * on macOS, and the FFI binding is refused native access without the other.
 *
 * This stays a module of its own so that Tao's `MainDispatcherFactory`, which outranks
 * `kotlinx-coroutines-swing`, never reaches the AWT demo's runtime classpath.
 */
val mainRuntimeClasspath = sourceSets.named("main").map { it.runtimeClasspath }

val run by
  tasks.registering(JavaExec::class) {
    group = "application"
    description = "Runs the Nucleus Tao desktop host fixture."

    classpath = mainRuntimeClasspath.get()
    mainClass = "org.maplibre.compose.nucleus.MainKt"
    jvmArgs(NATIVE_ACCESS_JVM_ARGS)
    if (System.getProperty("os.name").lowercase().startsWith("mac")) {
      jvmArgs("-XstartOnFirstThread")
    }
    javaLauncher = javaToolchains.launcherFor {
      languageVersion = JavaLanguageVersion.of(libs.versions.java.toolchain.get().toInt())
    }
  }
