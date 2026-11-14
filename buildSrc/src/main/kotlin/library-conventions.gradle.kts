import org.jetbrains.kotlin.gradle.dsl.KotlinBaseExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinMetadataTarget

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
        // Spatial K's Dokka HTML lives under /api/dokka/; /api/package-list 404s.
        create("spatial-k") { url("https://maplibre.org/spatial-k/api/dokka/") }
        create("maplibre-native") {
          url("https://maplibre.org/maplibre-native/android/api/")
          packageListUrl(
            "https://maplibre.org/maplibre-native/android/api/-map-libre%20-native%20-android/package-list",
          )
        }
      }
    }
  }
}

// Dokka 2.3.0's KotlinAdapter copies a compilation classpath onto a source set
// only when that source set is the compilation's primary set, or a bamboo
// intermediate with one associated compilation. Custom JVM-like intermediates
// such as androidJvmMain have a metadata compilation that KGP skips, so Dokka
// gets an empty classpath. Unresolved symbols are then warnings
// (https://github.com/Kotlin/dokka/issues/4455).
pluginManager.withPlugin("org.jetbrains.kotlin.multiplatform") {
  val kotlin = extensions.getByType<KotlinMultiplatformExtension>()
  dokka {
    dokkaSourceSets.configureEach {
      val sourceSetName = name
      val associatedCompilations =
        kotlin.targets
          .flatMap { it.compilations }
          .filter { compilation ->
            compilation.allKotlinSourceSets.any { it.name == sourceSetName }
          }
      val primaryCompilations = associatedCompilations.filter { compilation ->
        compilation.kotlinSourceSets.any { it.name == sourceSetName }
      }
      // A leaf source set already has its compilation classpath from KotlinAdapter.
      if (primaryCompilations.any { it.target !is KotlinMetadataTarget }) return@configureEach
      val leafMains = associatedCompilations.filter { compilation ->
        compilation.name == KotlinCompilation.MAIN_COMPILATION_NAME &&
          compilation.target !is KotlinMetadataTarget
      }
      // Mixing JVM jars into a Common/Native source set makes Dokka look for
      // java.io.Serializable on types that do not have it
      // (https://github.com/Kotlin/dokka/issues/4554).
      if (
        leafMains.isNotEmpty() &&
          leafMains.all {
            it.platformType == KotlinPlatformType.jvm ||
              it.platformType == KotlinPlatformType.androidJvm
          }
      ) {
        leafMains.forEach { compilation ->
          classpath.from({ compilation.compileDependencyFiles })
        }
      }
    }
  }
}
