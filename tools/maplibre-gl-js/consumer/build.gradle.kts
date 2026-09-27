plugins {
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.kotlin.composeCompiler)
  alias(libs.plugins.compose)
}

repositories {
  maven { url = uri("../../../build/js-test-maven") }
  mavenCentral()
  google()
}

kotlin {
  js {
    useEsModules()
    browser()
    binaries.executable()
  }
  sourceSets.jsMain.dependencies {
    implementation("org.maplibre.compose:maplibre-compose:0.0.0-SNAPSHOT")
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.jetbrains.compose.ui)
  }
}
