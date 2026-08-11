plugins {
  id("module-conventions")
  id(libs.plugins.kotlin.jvm.get().pluginId)
  id(libs.plugins.dokka.get().pluginId)
  id(libs.plugins.mavenPublish.get().pluginId)
}

mavenPublishing {
  pom {
    name = "MapLibre Compose for Linux"
    description = "Linux integrations for MapLibre Compose applications."
    url = "https://github.com/maplibre/maplibre-compose"
  }
}

kotlin {
  explicitApi()
  jvmToolchain(libs.versions.java.toolchain.get().toInt())
  compilerOptions { jvmTarget = project.getDesktopJvmTarget() }
}

dependencies {
  api(project(":lib:maplibre-compose"))
  implementation(libs.dbus.java.core)
  implementation(libs.dbus.java.transport.native.unixsocket)
  implementation(libs.kotlinx.coroutines.core)

  testImplementation(kotlin("test"))
  testImplementation(libs.kotlinx.coroutines.test)
}

tasks.register("jvmTest") { dependsOn(tasks.test) }
