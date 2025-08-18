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
  val releaseVersion = ext["base_tag"].toString().replace("v", "")
  val snapshotVersion = "${ext["next_patch_version"]}-SNAPSHOT"
  extras.set(
    mapOf(
      "release_version" to releaseVersion,
      "snapshot_version" to snapshotVersion,
      "maplibre_android_version" to libs.versions.maplibre.android.sdk.get(),
      "maplibre_ios_version" to project.properties["maplibreIosVersion"]!!.toString(),
      "maplibre_js_version" to libs.versions.maplibre.js.get(),
    )
  )
}

dokka { moduleName = "MapLibre Compose API Reference" }

tasks.register("generateDocs") {
  dependsOn("dokkaGenerate", "mkdocsBuild")
  doLast {
    copy {
      from(layout.buildDirectory.dir("mkdocs"))
      into(layout.buildDirectory.dir("docs"))
    }
    copy {
      from(layout.buildDirectory.dir("dokka/html"))
      into(layout.buildDirectory.dir("docs/api"))
    }
  }
}

dependencies {
  dokka(project(":lib:maplibre-compose:"))
  dokka(project(":lib:maplibre-compose-material3:"))
  dokka(project(":lib:maplibre-native-bindings"))
  dokka(project(":lib:maplibre-js-bindings"))
}
