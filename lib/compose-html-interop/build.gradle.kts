import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
  id("library-conventions")
  id(libs.plugins.kotlin.multiplatform.get().pluginId)
  id(libs.plugins.kotlin.composeCompiler.get().pluginId)
  id(libs.plugins.compose.get().pluginId)
  id(libs.plugins.mavenPublish.get().pluginId)
}

mavenPublishing {
  pom {
    name = "Compose HTML Interop"
    description = "Include an HTML element in a Compose Web UI."
    url = "https://github.com/maplibre/maplibre-compose"
  }
}

kotlin {
  js(IR) { browser() }
  @OptIn(ExperimentalWasmDsl::class) wasmJs { browser() }

  sourceSets {
    commonMain.dependencies { implementation(compose.foundation) }

    jsMain.dependencies { implementation(kotlin("stdlib-js")) }

    wasmJsMain.dependencies { implementation(libs.kotlinx.browser) }

    commonTest.dependencies {
      implementation(kotlin("test"))
      implementation(kotlin("test-common"))
      implementation(kotlin("test-annotations-common"))
    }
  }
}
