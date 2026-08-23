plugins {
  id("module-conventions")
  id(libs.plugins.android.classicLibrary.get().pluginId)
  id(libs.plugins.mavenPublish.get().pluginId)
}

mavenPublishing {
  pom {
    name = "MapLibre Compose Runtime (OpenGL, Android)"
    description = "The MapLibre Native FFI OpenGL runtime for MapLibre Compose on Android."
    url = "https://github.com/maplibre/maplibre-compose"
  }
}

android {
  namespace = "org.maplibre.compose.runtime.opengl"

  compileSdk = libs.versions.android.compileSdk.get().toInt()

  defaultConfig { minSdk = libs.versions.android.minSdk.get().toInt() }
}

dependencies {
  runtimeOnly(libs.maplibre.nativeFfi.runtimeOpenGl)
}
