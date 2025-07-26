import ru.vyarus.gradle.plugin.mkdocs.task.MkdocsTask

plugins {
  id(libs.plugins.spotless.get().pluginId)
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
  dokka(project(":lib:kotlin-maplibre-js"))
}

spotless {
  val modulePaths = listOf("demo-app", "lib/*", "buildSrc")
  kotlinGradle {
    target("*.gradle.kts", *(modulePaths.map { "${it}/*.gradle.kts" }).toTypedArray())
    ktfmt().googleStyle()
  }
  kotlin {
    target(*modulePaths.map { "${it}/src/**/*.kt" }.toTypedArray())
    ktfmt().googleStyle()
  }
  if (System.getProperty("os.name").contains("Mac OS X")) {
    format("swift") {
      target("iosApp/iosApp/**/*.swift")
      nativeCmd("swiftFormat", "/usr/bin/env", listOf("swift", "format"))
    }
  }
  format("markdown") {
    target("**/*.md")
    prettier(libs.versions.tool.prettier.get()).config(mapOf("proseWrap" to "always"))
  }
  yaml {
    target("**/*.yml", "**/*.yaml")
    prettier(libs.versions.tool.prettier.get())
  }
}

tasks.register("installGitHooks") {
  doLast {
    copy {
      from("${rootProject.projectDir}/scripts/pre-commit")
      into("${rootProject.projectDir}/.git/hooks")
    }
  }
}

tasks.named("clean") { doLast { delete("${rootProject.projectDir}/.git/hooks/pre-commit") } }
