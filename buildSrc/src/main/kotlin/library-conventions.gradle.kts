import org.jetbrains.kotlin.gradle.dsl.KotlinBaseExtension
import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation

plugins {
  id("module-conventions")
  id("org.jetbrains.dokka")
  id("maven-publish")
}

listOf("org.jetbrains.kotlin.jvm", "org.jetbrains.kotlin.multiplatform").forEach { pluginId ->
  pluginManager.withPlugin(pluginId) {
    extensions.configure<KotlinBaseExtension> {
      explicitApi()
      jvmToolchain(catalogVersionInt("java-toolchain"))

      @OptIn(ExperimentalAbiValidation::class)
      abiValidation {
        keepLocallyUnsupportedTargets.set(false)
        filters { exclude { byNames.add("**.ComposableSingletons**") } }
      }
    }
  }
}

dokka {
  dokkaSourceSets {
    configureEach {
      includes.from("MODULE.md")
      sourceLink {
        // Dokka appends the source path with a leading slash.
        val sourceRef = providers.gradleProperty("maplibreSourceRef").get()
        remoteUrl("https://github.com/maplibre/maplibre-compose/tree/$sourceRef")
        localDirectory.set(rootDir)
      }
      externalDocumentationLinks {
        create("spatial-k") { url("https://maplibre.org/spatial-k/api/") }
        create("maplibre-native") {
          url("https://maplibre.org/maplibre-native/android/api/")
          packageListUrl(
            "https://maplibre.org/maplibre-native/android/api/-map-libre%20-native%20-android/package-list"
          )
        }
      }
    }
  }
}
