plugins {
  id("module-conventions")
  id(libs.plugins.kotlin.jvm.get().pluginId)
  id(libs.plugins.dokka.get().pluginId)
  id(libs.plugins.mavenPublish.get().pluginId)
}

mavenPublishing {
  pom {
    name = "MapLibre Compose Location Runtime for macOS"
    description = "macOS location backend for MapLibre Compose applications."
    url = "https://github.com/maplibre/maplibre-compose"
  }
}

kotlin {
  explicitApi()
  jvmToolchain(libs.versions.java.toolchain.get().toInt())
  compilerOptions { jvmTarget = project.getDesktopJvmTarget() }
}

dependencies {
  api(project(":lib:location"))
  implementation(libs.lwjgl.core)
  implementation(libs.kotlinx.coroutines.core)

  testImplementation(kotlin("test"))
  testImplementation(libs.kotlinx.coroutines.test)
  testRuntimeOnly(
    "org.lwjgl:lwjgl:${libs.versions.lwjgl.get()}:" +
      DesktopHostPlatform.current().lwjglNativesClassifier
  )
}

tasks.test { jvmArgs(NATIVE_ACCESS_JVM_ARGS) }

tasks.register("jvmTest") { dependsOn(tasks.test) }
