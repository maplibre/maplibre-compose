import org.jetbrains.compose.reload.gradle.ComposeHotRun

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
  implementation(compose.desktop.currentOs)
  implementation(libs.kotlinx.coroutines.swing)

  runtimeOnly(project(":lib:${desktopHostPlatform.runtimeArtifactId(desktopRenderBackend)}"))
}

tasks.withType<ComposeHotRun>().configureEach {
  mainClass = "org.maplibre.compose.demoapp.MainKt"
  jvmArgs(NATIVE_ACCESS_JVM_ARGS)
}

compose.desktop {
  application {
    mainClass = "org.maplibre.compose.demoapp.MainKt"
    jvmArgs += NATIVE_ACCESS_JVM_ARGS
  }
}
