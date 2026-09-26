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
  --api-roots <a,b>   Roots whose public declarations count as API surface. Default: lib
  --out <file>        Write the JSON snapshot here. Default: print it to stdout.
  --churn-days <n>    Window for git churn, ending at the measured commit. Default: 180
  --top <n>           Length of each ranked list. Default: 20
"""

data class Options(
  val repo: Path,
  val ref: String?,
  val roots: List<String>,
  val apiRoots: List<String>,
  val out: Path?,
  val churnDays: Long,
  val top: Int,
)

fun parseOptions(args: Array<String>): Options {
  var repo = Path.of(".")
  var ref: String? = null
  var roots = listOf("lib", "demo-app")
  var apiRoots = listOf("lib")
  var out: Path? = null
  var churnDays = 180L
  var top = 20
  val iterator = args.iterator()
  fun next(flag: String) =
    if (iterator.hasNext()) iterator.next() else usageError("$flag needs a value")
  while (iterator.hasNext()) {
    when (val flag = iterator.next()) {
      "--repo" -> repo = Path.of(next(flag))
      "--ref" -> ref = next(flag)
      "--roots" -> roots = next(flag).splitList()
      "--api-roots" -> apiRoots = next(flag).splitList()
      "--out" -> out = Path.of(next(flag))
      "--churn-days" -> churnDays = next(flag).toLongOrNull() ?: usageError("$flag needs a number")
      "--top" -> top = next(flag).toIntOrNull() ?: usageError("$flag needs a number")
      "--help",
      "-h" -> {
        println(USAGE.trim())
        exitProcess(0)
      }
      else -> usageError("unknown option $flag")
    }
  }
  return Options(repo.absolute().normalize(), ref, roots, apiRoots, out, churnDays, top)
}

private fun String.splitList() = split(',').map { it.trim() }.filter { it.isNotEmpty() }

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
  val git = GitRepository(options.repo)
  val ref = options.ref
  // A working tree without commits still measures; a ref that does not resolve is a mistake.
  val commit =
    if (ref == null) runCatching { git.commit("HEAD") }.getOrNull()
    else runCatching { git.commit(ref) }.getOrElse { usageError("unknown ref $ref") }
  val commitDate = commit?.let { git.commitDate(it) }
  val since = commitDate?.minusDays(options.churnDays)

  val churn =
    if (commit == null || since == null) null
    else
      Churn(options.churnDays, since.toString(), git.commitsPerFile(commit, since, options.roots))

  val files =
    if (ref == null) {
      analyzeTree(options.repo, options.roots)
    } else {
      val export = Files.createTempDirectory("code-metrics")
      try {
        git.export(ref, options.roots, export)
        analyzeTree(export, options.roots)
      } finally {
        export.toFile().deleteRecursively()
      }
    }

  val (summary, sections) = aggregate(files, options.apiRoots, churn, options.top)
  return Snapshot(
    generatedAt = OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
    ref = ref,
    commit = commit,
    commitDate = commitDate?.toString(),
    describe = commit?.let { git.describe(it) },
    dirty = if (ref == null && commit != null) git.isDirty(options.roots) else null,
    roots = options.roots,
    apiRoots = options.apiRoots,
    summary = summary,
    sourceSets = sections.sourceSets,
    packages = sections.packages,
    packageGraph = sections.packageGraph,
    abstractions = sections.abstractions,
    distributions = sections.distributions,
    largest = sections.largest,
    churn = sections.churn,
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
      "abstractions / single impl / no impl" to
        "${summary.abstractions} / ${summary.abstractionsWithSingleImplementation} / ${summary.abstractionsWithNoImplementation}",
      "public / internal declarations" to
        "${summary.publicDeclarations} / ${summary.internalDeclarations}",
      "kdoc coverage" to "%.2f".format(summary.kdocCoverage),
      "todo / suppress" to "${summary.todoComments} / ${summary.suppressAnnotations}",
    )
  val width = rows.maxOf { it.first.length }
  for ((label, value) in rows) System.err.println("${label.padEnd(width)}  $value")
}
