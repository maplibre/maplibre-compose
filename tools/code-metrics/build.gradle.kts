plugins {
  id("module-conventions")
  id(libs.plugins.kotlin.jvm.get().pluginId)
  id(libs.plugins.kotlin.serialization.get().pluginId)
  application
}

kotlin { jvmToolchain(libs.versions.java.toolchain.get().toInt()) }

application { mainClass = "org.maplibre.compose.metrics.MainKt" }

dependencies {
  implementation(libs.kotlin.compiler)
  implementation(libs.detekt.metrics)
  implementation(libs.kotlinx.serialization.json)

  testImplementation(kotlin("test"))
}

tasks.test { useJUnitPlatform() }
