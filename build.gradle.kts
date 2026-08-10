plugins {
  id(libs.plugins.dokka.get().pluginId)
  id("module-conventions")
}

dokka { moduleName = "MapLibre Compose API Reference" }

dependencies {
  dokka(project(":lib:maplibre-compose"))
  dokka(project(":lib:maplibre-compose-material3"))
  dokka(project(":lib:maplibre-compose-gms"))
}
