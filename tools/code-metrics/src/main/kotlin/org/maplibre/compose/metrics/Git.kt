package org.maplibre.compose.metrics

import java.nio.file.Files
import java.nio.file.Path
import java.time.OffsetDateTime
import kotlin.io.path.absolutePathString

/** Runs a command and returns its stdout, failing on a non-zero exit. */
fun run(vararg command: String, workingDirectory: Path? = null): String {
  val process =
    ProcessBuilder(*command)
      .apply { workingDirectory?.let { directory(it.toFile()) } }
      .redirectError(ProcessBuilder.Redirect.INHERIT)
      .start()
  val output = process.inputStream.bufferedReader().readText()
  val status = process.waitFor()
  check(status == 0) { "${command.joinToString(" ")} exited with $status" }
  return output
}

class GitRepository(val root: Path) {
  fun git(vararg args: String): String = run("git", "-C", root.absolutePathString(), *args)

  fun commit(ref: String): String = git("rev-parse", "--verify", "$ref^{commit}").trim()

  fun commitDate(ref: String): OffsetDateTime =
    OffsetDateTime.parse(git("log", "-1", "--format=%cI", ref).trim())

  fun describe(ref: String): String = git("describe", "--tags", "--always", ref).trim()

  /** Whether the working tree under [paths] differs from HEAD, counting untracked files. */
  fun isDirty(paths: List<String>): Boolean =
    git("status", "--porcelain", "--", *paths.toTypedArray()).isNotBlank()

  /** Extracts the tracked files under [paths] at [ref] into [into]. */
  fun export(ref: String, paths: List<String>, into: Path) {
    val archive = Files.createTempFile("code-metrics", ".tar")
    try {
      git(
        "archive",
        "--format=tar",
        "-o",
        archive.absolutePathString(),
        ref,
        "--",
        *paths.toTypedArray(),
      )
      run("tar", "-xf", archive.absolutePathString(), "-C", into.absolutePathString())
    } finally {
      Files.deleteIfExists(archive)
    }
  }

  /** Commits per file that touched [paths] between [since] and [ref]. */
  fun commitsPerFile(ref: String, since: OffsetDateTime, paths: List<String>): Map<String, Int> =
    git(
        "log",
        ref,
        "--since=$since",
        "--format=",
        "--name-only",
        "--",
        *paths.toTypedArray(),
      )
      .lineSequence()
      .filter { it.isNotBlank() }
      .groupingBy { it }
      .eachCount()
}
