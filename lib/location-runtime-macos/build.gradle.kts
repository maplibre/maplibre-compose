plugins {
  id("library-conventions")
  id(libs.plugins.kotlin.jvm.get().pluginId)
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
  compilerOptions { jvmTarget = project.getDesktopJvmTarget() }
}

dependencies {
  api(project(":lib:location"))
  implementation(libs.lwjgl.core)
  implementation(libs.kotlinx.coroutines.core)

  testImplementation(kotlin("test"))
  testImplementation(libs.kotlinx.coroutines.test)
  // Lets macOS test runs call Core Location through LWJGL's Objective-C bindings.
  testRuntimeOnly(
    "org.lwjgl:lwjgl:${libs.versions.lwjgl.get()}:" +
      DesktopHostPlatform.MacosArm64.lwjglNativesClassifier
  )
}

tasks.test { jvmArgs(NATIVE_ACCESS_JVM_ARGS) }

tasks.register("jvmTest") { dependsOn(tasks.test) }
