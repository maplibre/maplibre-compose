import ru.vyarus.gradle.plugin.mkdocs.task.MkdocsTask

plugins {
  id(libs.plugins.dokka.get().pluginId)
  id(libs.plugins.mkdocs.get().pluginId)
  id("module-conventions")
}

mkdocs {
  sourcesDir = "docs"
  strict = true
  publish {
    docPath = null // single version site
  }
}

tasks.withType<MkdocsTask>().configureEach {
  extras.set(
    mapOf(
      "release_version" to providers.gradleProperty("maplibreReleaseVersion").get(),
      "snapshot_version" to providers.gradleProperty("maplibreSnapshotVersion").get(),
      "maplibre_android_version" to libs.versions.maplibre.android.sdk.get(),
      "maplibre_ios_version" to libs.versions.maplibre.ios.get(),
      "maplibre_js_version" to libs.versions.maplibre.js.get(),
    )
  )
}

dokka { moduleName = "MapLibre Compose API Reference" }

tasks.register<Sync>("generateDocs") {
  dependsOn("dokkaGenerate", "mkdocsBuild")
  into(layout.buildDirectory.dir("docs"))
  from(layout.buildDirectory.dir("mkdocs"))
  from(layout.buildDirectory.dir("dokka/html")) { into("api") }
  // docs/api/index.html is a placeholder MkDocs routes to; Dokka's index wins.
  duplicatesStrategy = DuplicatesStrategy.INCLUDE
}

dependencies {
  dokka(project(":lib:maplibre-js-bindings"))
  dokka(project(":lib:maplibre-compose"))
  dokka(project(":lib:maplibre-compose-material3"))
  dokka(project(":lib:maplibre-compose-gms"))
}
