import groovy.json.JsonOutput
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import org.gradle.work.DisableCachingByDefault

/** Embeds the build checkout, not the checkout used later to capture its measurements. */
@DisableCachingByDefault(because = "Reads the current Git checkout at execution time")
abstract class GenerateBenchmarkBuildInfo : DefaultTask() {
  @get:Internal abstract val repository: DirectoryProperty
  @get:Input abstract val dependencyVersions: MapProperty<String, String>
  @get:OutputDirectory abstract val outputDirectory: DirectoryProperty
  @get:Inject abstract val processes: ExecOperations

  init {
    outputs.upToDateWhen { false }
  }

  private fun git(vararg arguments: String): String {
    val output = ByteArrayOutputStream()
    processes.exec {
      workingDir(repository.get().asFile)
      commandLine("git", *arguments)
      standardOutput = output
    }
    return output.toString(Charsets.UTF_8).trim()
  }

  @TaskAction
  fun generate() {
    val metadata =
      JsonOutput.toJson(
        mapOf(
          "commit" to git("rev-parse", "HEAD"),
          "dirty" to git("status", "--porcelain", "--untracked-files=normal").isNotEmpty(),
          "dependency_versions" to dependencyVersions.get().toSortedMap(),
        )
      )
    val literal = JsonOutput.toJson(metadata).replace("$", "\\$")
    val source =
      "package org.maplibre.compose.benchmark\n\n" +
        "internal const val BenchmarkBuildInfo: String = $literal\n"
    val output = outputDirectory.file("BenchmarkBuildInfo.kt").get().asFile
    output.parentFile.mkdirs()
    if (!output.exists() || output.readText() != source) output.writeText(source)
  }
}
