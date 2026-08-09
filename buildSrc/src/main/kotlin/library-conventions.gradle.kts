plugins {
  id("module-conventions")
  id("org.jetbrains.kotlin.multiplatform")
  id("org.jetbrains.kotlin.plugin.serialization")
  id("org.jetbrains.dokka")
  id("maven-publish")
}

kotlin {
  explicitApi()

  jvmToolchain(catalogVersionInt("java-toolchain"))

  compilerOptions {
    freeCompilerArgs.addAll("-Xexpect-actual-classes", "-Xconsistent-data-class-copy-visibility")
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
