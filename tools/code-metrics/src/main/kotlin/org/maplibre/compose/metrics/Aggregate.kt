package org.maplibre.compose.metrics

import kotlin.math.ceil

fun aggregate(files: List<FileFacts>, top: Int): Pair<Summary, Sections> {
  val main = files.filter { !it.source.isTest }
  val test = files.filter { it.source.isTest }

  val sourceSets = sourceSetReports(files)
  val graph = packageGraph(main)
  val packages = packageReports(main, graph)
  val modules = moduleReports(main)
  val distributions = distributions(main)
  val largest = largest(main, packages, top)

  val mainLoc = main.sumOf { it.loc }
  val testLoc = test.sumOf { it.loc }
  val sloc = main.sumOf { it.sloc }
  val lloc = main.sumOf { it.lloc }
  val cloc = main.sumOf { it.cloc }
  val mcc = main.sumOf { it.cyclomaticComplexity }
  val cycles = graph.cycles

  fun dist(name: String) = distributions.getValue(name)

  val summary =
    Summary(
      files = main.size,
      loc = mainLoc,
      sloc = sloc,
      lloc = lloc,
      cloc = cloc,
      testLoc = testLoc,
      testToMainLocRatio = ratio(testLoc, mainLoc),
      commentToSourceRatio = ratio(cloc, sloc),
      packages = packages.size,
      types = main.sumOf { it.types.size },
      functions = main.sumOf { it.functions.size },
      cyclomaticComplexity = mcc,
      cognitiveComplexity = main.sumOf { it.cognitiveComplexity },
      cyclomaticPer1000Lloc = if (lloc == 0) 0.0 else mcc * 1000.0 / lloc,
      functionLinesP90 = dist("functionLines").p90,
      functionLinesMax = dist("functionLines").max,
      functionCyclomaticP90 = dist("functionCyclomaticComplexity").p90,
      functionCyclomaticMax = dist("functionCyclomaticComplexity").max,
      functionCognitiveP90 = dist("functionCognitiveComplexity").p90,
      functionCognitiveMax = dist("functionCognitiveComplexity").max,
      functionParametersP90 = dist("functionParameters").p90,
      functionParametersMax = dist("functionParameters").max,
      fileLocP90 = dist("fileLoc").p90,
      fileLocMax = dist("fileLoc").max,
      typeLinesP90 = dist("typeLines").p90,
      typeLinesMax = dist("typeLines").max,
      cognitiveComplexMethods = main.sumOf { it.cognitiveComplexMethods() },
      cyclomaticComplexMethods =
        main.sumOf { file ->
          file.functions.count { it.cyclomaticComplexity > detektDefaults.cyclomaticComplexMethod }
        },
      longMethods =
        main.sumOf { file -> file.functions.count { it.lines > detektDefaults.longMethod } },
      packageEdges = graph.edges.size,
      packageCycles = cycles.size,
      packagesInCycles = cycles.flatten().toSet().size,
      bidirectionalPackagePairs = graph.bidirectionalPairs.size,
      meanPackageInstability = packages.map { it.instability }.average().orZero(),
      packageSourceSetsMean = packages.map { it.sourceSets.size.toDouble() }.average().orZero(),
      packageSourceSetsMax = packages.maxOfOrNull { it.sourceSets.size } ?: 0,
      expectDeclarations = main.sumOf { it.expectDeclarations },
      actualDeclarations = main.sumOf { it.actualDeclarations },
      todoComments = files.sumOf { it.todoCount },
      suppressAnnotations = files.sumOf { it.suppressCount },
    )
  return summary to Sections(sourceSets, packages, modules, graph, distributions, largest)
}

data class Sections(
  val sourceSets: List<SourceSetReport>,
  val packages: List<PackageReport>,
  val modules: List<ModuleReport>,
  val packageGraph: PackageGraph,
  val distributions: Map<String, Distribution>,
  val largest: Largest,
)

private fun sourceSetReports(files: List<FileFacts>): List<SourceSetReport> =
  files
    .groupBy { it.source.module to it.source.sourceSet }
    .map { (key, group) ->
      SourceSetReport(
        module = key.first,
        name = key.second,
        isTest = group.first().source.isTest,
        files = group.size,
        loc = group.sumOf { it.loc },
        sloc = group.sumOf { it.sloc },
        lloc = group.sumOf { it.lloc },
        cloc = group.sumOf { it.cloc },
        cyclomaticComplexity = group.sumOf { it.cyclomaticComplexity },
        cognitiveComplexity = group.sumOf { it.cognitiveComplexity },
        types = group.sumOf { it.types.size },
        functions = group.sumOf { it.functions.size },
        expectDeclarations = group.sumOf { it.expectDeclarations },
        actualDeclarations = group.sumOf { it.actualDeclarations },
      )
    }
    .sortedWith(compareBy({ it.module }, { it.name }))

/**
 * Maps imports to the scanned packages they refer to. An import names a package member or a nested
 * member of a declared type; anything else, such as a Java package under a scanned tree or a
 * library type, is external.
 */
internal class PackageIndex(
  private val packages: Set<String>,
  private val typePackages: Map<String, String>,
) {
  constructor(
    files: List<FileFacts>
  ) : this(
    packages = files.map { it.packageName }.toSet(),
    typePackages =
      files
        .flatMap { file -> file.types.map { file.qualify(it.name) to file.packageName } }
        .toMap(),
  )

  /** The scanned package [import] refers to, or null when it is external. */
  fun packageOf(import: Import): String? {
    val qualifier =
      if (import.allUnder) import.fqName else import.fqName.substringBeforeLast('.', "")
    return if (qualifier in packages) qualifier else typePackages[qualifier]
  }
}

/** The qualified name of a type declared as [nestedName] in this file's package. */
internal fun FileFacts.qualify(nestedName: String): String =
  if (packageName.isEmpty()) nestedName else "$packageName.$nestedName"

internal fun packageGraph(main: List<FileFacts>): PackageGraph {
  val index = PackageIndex(main)
  val counts = mutableMapOf<Pair<String, String>, Int>()
  for (file in main) {
    for (import in file.imports) {
      val target = index.packageOf(import) ?: continue
      if (target != file.packageName) counts.merge(file.packageName to target, 1, Int::plus)
    }
  }
  val edges =
    counts
      .map { (pair, count) -> PackageEdge(pair.first, pair.second, count) }
      .sortedWith(compareBy({ it.from }, { it.to }))
  val adjacency = edges.groupBy({ it.from }, { it.to })
  val pairs = edges.map { it.from to it.to }.toSet()
  val bidirectional =
    pairs
      .filter { (a, b) -> a < b && (b to a) in pairs }
      .map { (a, b) -> listOf(a, b) }
      .sortedBy { it.first() }
  val cycles =
    stronglyConnectedComponents(main.map { it.packageName }.distinct().sorted(), adjacency)
      .filter { it.size > 1 }
      .map { it.sorted() }
      .sortedBy { it.first() }
  return PackageGraph(edges, cycles, bidirectional)
}

/** Tarjan's algorithm. */
internal fun stronglyConnectedComponents(
  nodes: List<String>,
  adjacency: Map<String, List<String>>,
): List<List<String>> {
  var nextIndex = 0
  val index = mutableMapOf<String, Int>()
  val lowLink = mutableMapOf<String, Int>()
  val stack = ArrayDeque<String>()
  val onStack = mutableSetOf<String>()
  val components = mutableListOf<List<String>>()

  fun connect(node: String) {
    index[node] = nextIndex
    lowLink[node] = nextIndex
    nextIndex++
    stack.addLast(node)
    onStack += node
    for (next in adjacency[node].orEmpty()) {
      if (next !in index) {
        connect(next)
        lowLink[node] = minOf(lowLink.getValue(node), lowLink.getValue(next))
      } else if (next in onStack) {
        lowLink[node] = minOf(lowLink.getValue(node), index.getValue(next))
      }
    }
    if (lowLink[node] == index[node]) {
      val component = mutableListOf<String>()
      do {
        val member = stack.removeLast()
        onStack -= member
        component += member
      } while (member != node)
      components += component
    }
  }

  for (node in nodes) if (node !in index) connect(node)
  return components
}

private fun packageReports(main: List<FileFacts>, graph: PackageGraph): List<PackageReport> {
  val index = PackageIndex(main)
  val dependsOn = graph.edges.groupBy({ it.from }, { it.to })
  val dependedOnBy = graph.edges.groupBy({ it.to }, { it.from })
  return main
    .groupBy { it.packageName }
    .map { (name, group) ->
      val ce = dependsOn[name].orEmpty().sorted()
      val ca = dependedOnBy[name].orEmpty().sorted()
      PackageReport(
        name = name,
        files = group.size,
        loc = group.sumOf { it.loc },
        types = group.sumOf { it.types.size },
        functions = group.sumOf { it.functions.size },
        sourceSets = group.map { "${it.source.module}:${it.source.sourceSet}" }.distinct().sorted(),
        dependsOn = ce,
        dependedOnBy = ca,
        instability = ratio(ce.size, ca.size + ce.size),
        externalImports =
          group
            .flatMap { it.imports }
            .filter { index.packageOf(it) == null }
            .map { it.fqName }
            .distinct()
            .size,
      )
    }
    .sortedBy { it.name }
}

/**
 * Maps imports to the scanned modules that declare them. A type resolves to its module. A package
 * member resolves to the importing module when that module has the package, and otherwise to every
 * module that has it.
 */
internal class ModuleIndex(files: List<FileFacts>) {
  private val typeModules =
    files.flatMap { file -> file.types.map { file.qualify(it.name) to file.source.module } }.toMap()
  private val packageModules =
    files.groupBy({ it.packageName }, { it.source.module }).mapValues { it.value.toSet() }

  fun modulesOf(import: Import, from: String): Set<String> {
    typeModules[import.fqName]?.let {
      return setOf(it)
    }
    val qualifier =
      if (import.allUnder) import.fqName else import.fqName.substringBeforeLast('.', "")
    typeModules[qualifier]?.let {
      return setOf(it)
    }
    val modules = packageModules[qualifier] ?: return emptySet()
    return if (from in modules) setOf(from) else modules
  }
}

internal fun moduleReports(main: List<FileFacts>): List<ModuleReport> {
  val index = ModuleIndex(main)
  val dependsOn =
    main
      .groupBy { it.source.module }
      .mapValues { (module, files) ->
        files.flatMap { file -> file.imports.flatMap { index.modulesOf(it, module) } }.toSet() -
          module
      }
  return dependsOn.keys.sorted().map { module ->
    val ce = dependsOn.getValue(module).sorted()
    val ca = dependsOn.filterValues { module in it }.keys.sorted()
    ModuleReport(module, ce, ca, ratio(ce.size, ca.size + ce.size))
  }
}

private fun functionId(path: String, function: FunctionFacts) =
  "$path:${function.line}:${function.name}"

private fun distributions(main: List<FileFacts>): Map<String, Distribution> {
  val functions = main.flatMap { file -> file.functions.map { file.source.relativePath to it } }
  val types = main.flatMap { file -> file.types.map { file.source.relativePath to it } }
  fun ofFunctions(value: (FunctionFacts) -> Int) =
    distribution(functions.map { (path, f) -> Ranked(functionId(path, f), value(f)) })
  return linkedMapOf(
    "fileLoc" to distribution(main.map { Ranked(it.source.relativePath, it.loc) }),
    "typeLines" to distribution(types.map { (path, t) -> Ranked("$path:${t.name}", t.lines) }),
    "functionLines" to ofFunctions { it.lines },
    "functionCyclomaticComplexity" to ofFunctions { it.cyclomaticComplexity },
    "functionCognitiveComplexity" to ofFunctions { it.cognitiveComplexity },
    "functionParameters" to ofFunctions { it.parameters },
  )
}

internal fun distribution(values: List<Ranked>): Distribution {
  if (values.isEmpty()) return Distribution(0, 0.0, 0, 0, 0, 0, null)
  val sorted = values.map { it.value }.sorted()
  fun percentile(p: Double) = sorted[maxOf(0, ceil(p * sorted.size).toInt() - 1)]
  val max = values.maxBy { it.value }
  return Distribution(
    count = sorted.size,
    mean = sorted.average(),
    p50 = percentile(0.5),
    p90 = percentile(0.9),
    p99 = percentile(0.99),
    max = max.value,
    maxName = max.name,
  )
}

private fun FileFacts.cognitiveComplexMethods() = functions.count {
  it.cognitiveComplexity > detektDefaults.cognitiveComplexMethod
}

fun fileReports(files: List<FileFacts>): List<FileReport> =
  files
    .filter { !it.source.isTest }
    .map { file ->
      FileReport(
        path = file.source.relativePath,
        module = file.source.module,
        sourceSet = file.source.sourceSet,
        packageName = file.packageName,
        loc = file.loc,
        functions = file.functions.size,
        cognitiveComplexity = file.cognitiveComplexity,
      )
    }

private fun largest(main: List<FileFacts>, packages: List<PackageReport>, top: Int): Largest {
  fun List<Ranked>.top() = sortedByDescending { it.value }.take(top)
  val types = main.flatMap { file -> file.types.map { file.source.relativePath to it } }
  val functions = main.flatMap { file -> file.functions.map { file.source.relativePath to it } }
  return Largest(
    filesByLoc = main.map { Ranked(it.source.relativePath, it.loc) }.top(),
    typesByLines = types.map { (path, t) -> Ranked("$path:${t.name}", t.lines) }.top(),
    functionsByLines = functions.map { (path, f) -> Ranked(functionId(path, f), f.lines) }.top(),
    functionsByCognitiveComplexity =
      functions.map { (path, f) -> Ranked(functionId(path, f), f.cognitiveComplexity) }.top(),
    packagesByTypes = packages.map { Ranked(it.name, it.types) }.top(),
  )
}

private fun ratio(numerator: Int, denominator: Int): Double =
  if (denominator == 0) 0.0 else numerator.toDouble() / denominator

private fun Double.orZero(): Double = if (isNaN()) 0.0 else this
