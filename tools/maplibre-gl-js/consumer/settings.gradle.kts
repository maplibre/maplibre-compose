rootProject.name = "maplibre-js-consumer"

pluginManagement {
  repositories {
    gradlePluginPortal()
    mavenCentral()
    google()
  }
}

dependencyResolutionManagement {
  versionCatalogs { create("libs") { from(files("../../../gradle/libs.versions.toml")) } }
}
