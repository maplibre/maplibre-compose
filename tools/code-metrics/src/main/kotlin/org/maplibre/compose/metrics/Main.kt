package org.maplibre.compose.metrics

import java.nio.file.Files
import java.nio.file.Path
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import kotlin.io.path.absolute
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.system.exitProcess
import kotlinx.serialization.json.Json

private const val USAGE =
  """
Usage: code-metrics [options]

  --repo <dir>        Repository to measure. Default: the working directory.
  --ref <git ref>     Measure the tracked files at this commit instead of the working tree.
  --roots <a,b>       Directories to scan, relative to the repository. Default: lib,demo-app
  --out <file>        Write the JSON snapshot here. Default: print it to stdout.
  --top <n>           Length of each ranked list. Default: 20
"""

data class Options(
  val repo: Path,
  val ref: String?,
  val roots: List<String>,
  val out: Path?,
  val top: Int,
)

fun parseOptions(args: Array<String>): Options {
  var repo = Path.of(".")
  var ref: String? = null
  var roots = listOf("lib", "demo-app")
  var out: Path? = null
  var top = 20
  val iterator = args.iterator()
  fun next(flag: String) =
    if (iterator.hasNext()) iterator.next() else usageError("$flag needs a value")
  while (iterator.hasNext()) {
    when (val flag = iterator.next()) {
      "--repo" -> repo = Path.of(next(flag))
      "--ref" -> ref = next(flag)
      "--roots" -> roots = next(flag).split(',').map { it.trim() }.filter { it.isNotEmpty() }
      "--out" -> out = Path.of(next(flag))
      "--top" -> top = next(flag).toIntOrNull() ?: usageError("$flag needs a number")
      "--help",
      "-h" -> {
        println(USAGE.trim())
        exitProcess(0)
      }
      else -> usageError("unknown option $flag")
    }
  }
  return Options(repo.absolute().normalize(), ref, roots, out, top)
}

private fun usageError(message: String): Nothing {
  System.err.println("code-metrics: $message\n\n${USAGE.trim()}")
  exitProcess(2)
}

private val json = Json {
  prettyPrint = true
  encodeDefaults = true
}

fun main(args: Array<String>) {
  val options = parseOptions(args)
  val snapshot = measure(options)
  val json = json.encodeToString(snapshot)
  val out = options.out
  if (out == null) {
    println(json)
  } else {
    out.absolute().parent?.createDirectories()
    out.writeText(json + "\n")
    printSummary(snapshot)
    System.err.println("Wrote ${out.absolute().normalize()}")
  }
}

fun measure(options: Options): Snapshot {
  // Roots are relative to the repository, wherever --repo pointed inside it.
  val root = runCatching {
    Path.of(GitRepository(options.repo).git("rev-parse", "--show-toplevel").trim())
  }
    .getOrElse { usageError("${options.repo} is not in a git repository") }
  val git = GitRepository(root)
  val ref = options.ref
  // A repository without commits still measures; a ref that does not resolve is a mistake.
  val commit =
    if (ref == null) runCatching { git.commit("HEAD") }.getOrNull()
    else runCatching { git.commit(ref) }.getOrElse { usageError("unknown ref $ref") }
  val commitDate = commit?.let { git.commitDate(it) }

  val files =
    if (ref == null) {
      analyzeTree(root, options.roots)
    } else {
      val export = Files.createTempDirectory("code-metrics")
      try {
        git.export(ref, options.roots, export)
        analyzeTree(export, options.roots)
      } finally {
        export.toFile().deleteRecursively()
      }
    }

  val (summary, sections) = aggregate(files, options.top)
  return Snapshot(
    generatedAt = OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
    ref = ref,
    commit = commit,
    commitDate = commitDate?.toString(),
    describe = commit?.let { git.describe(it) },
    dirty = if (ref == null && commit != null) git.isDirty(options.roots) else null,
    roots = options.roots,
    summary = summary,
    sourceSets = sections.sourceSets,
    packages = sections.packages,
    packageGraph = sections.packageGraph,
    distributions = sections.distributions,
    largest = sections.largest,
  )
}

fun analyzeTree(root: Path, roots: List<String>): List<FileFacts> =
  KotlinParser().use { parser ->
    discoverSourceFiles(root, roots).map { source ->
      analyzeFile(source, parser.parse(source.relativePath, source.path.readText()))
    }
  }

fun printSummary(snapshot: Snapshot) {
  val summary = snapshot.summary
  val rows =
    listOf(
      "commit" to (snapshot.describe ?: "working tree"),
      "files (main)" to summary.files,
      "loc (main) / test" to "${summary.loc} / ${summary.testLoc}",
      "comment/source ratio" to "%.2f".format(summary.commentToSourceRatio),
      "packages / types / functions" to
        "${summary.packages} / ${summary.types} / ${summary.functions}",
      "cyclomatic per 1000 lloc" to "%.1f".format(summary.cyclomaticPer1000Lloc),
      "function lines p90 / max" to "${summary.functionLinesP90} / ${summary.functionLinesMax}",
      "function cognitive p90 / max" to
        "${summary.functionCognitiveP90} / ${summary.functionCognitiveMax}",
      "file loc p90 / max" to "${summary.fileLocP90} / ${summary.fileLocMax}",
      "type lines p90 / max" to "${summary.typeLinesP90} / ${summary.typeLinesMax}",
      "package edges / cycles / in cycles" to
        "${summary.packageEdges} / ${summary.packageCycles} / ${summary.packagesInCycles}",
      "package source sets mean / max" to
        "%.1f / %d".format(summary.packageSourceSetsMean, summary.packageSourceSetsMax),
      "expect / actual" to "${summary.expectDeclarations} / ${summary.actualDeclarations}",
      "todo / suppress" to "${summary.todoComments} / ${summary.suppressAnnotations}",
    )
  val width = rows.maxOf { it.first.length }
  for ((label, value) in rows) System.err.println("${label.padEnd(width)}  $value")
}
