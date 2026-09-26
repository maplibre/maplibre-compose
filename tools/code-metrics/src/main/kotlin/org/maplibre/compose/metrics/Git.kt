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

  /**
   * Commits per file that touched [paths] between [since] and [ref], keyed by each file's path at
   * [ref]. Touches before a rename count toward the renamed file.
   */
  fun commitsPerFile(ref: String, since: OffsetDateTime, paths: List<String>): Map<String, Int> {
    val log =
      git(
        "log",
        ref,
        "--since=$since",
        "--format=",
        "--name-status",
        "-M",
        "--",
        *paths.toTypedArray(),
      )
    return countTouches(log.lineSequence())
  }
}

/**
 * Counts the files in `git log --name-status -M` output, newest commit first, following renames
 * forward so that a file's history is counted under its latest path.
 */
internal fun countTouches(statusLines: Sequence<String>): Map<String, Int> {
  val renamedTo = mutableMapOf<String, String>()
  fun current(path: String): String {
    var name = path
    while (true) name = renamedTo[name] ?: return name
  }
  val counts = mutableMapOf<String, Int>()
  for (line in statusLines) {
    val fields = line.split('\t')
    if (fields.size < 2 || fields[0].isEmpty()) continue
    val status = fields[0][0]
    val path = current(fields.last())
    counts.merge(path, 1, Int::plus)
    // Older commits, which come later, name the file by its path before this rename or copy.
    // A file renamed away and back would map to itself; skipping that keeps the chains acyclic.
    val old = fields[1]
    if ((status == 'R' || status == 'C') && fields.size >= 3 && old != path) renamedTo[old] = path
  }
  return counts
}
