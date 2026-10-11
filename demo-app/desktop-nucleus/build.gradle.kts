import org.jetbrains.compose.reload.gradle.ComposeHotRun

plugins {
  id("module-conventions")
  id(libs.plugins.kotlin.jvm.get().pluginId)
  id(libs.plugins.kotlin.composeCompiler.get().pluginId)
  id(libs.plugins.compose.get().pluginId)
  id(libs.plugins.nucleus.get().pluginId)
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
  implementation(libs.jetbrains.compose.material3)
  implementation(libs.nucleus.application)
  implementation(libs.nucleus.decoratedWindowTao)

  runtimeOnly(compose.desktop.currentOs)
  runtimeOnly(project(":lib:${desktopHostPlatform.runtimeArtifactId(desktopRenderBackend)}"))
}

nucleus.application {
  mainClass = "org.maplibre.compose.nucleus.MainKt"
  // Nucleus defaults to the Gradle daemon's JDK rather than the Kotlin toolchain.
  javaHome =
    javaToolchains
      .launcherFor {
        languageVersion.set(JavaLanguageVersion.of(libs.versions.java.toolchain.get().toInt()))
      }
      .get()
      .metadata
      .installationPath
      .asFile
      .absolutePath
  if (desktopHostPlatform == DesktopHostPlatform.MacosArm64) {
    // Nucleus adds this to hot reload, but not the ordinary JVM launcher.
    jvmArgs += "-XstartOnFirstThread"
  }
}

tasks.withType<ComposeHotRun>().configureEach {
  // Nucleus supplies the main class and macOS first-thread flag for hot reload.
  jvmArgs(NATIVE_ACCESS_JVM_ARGS)
}
