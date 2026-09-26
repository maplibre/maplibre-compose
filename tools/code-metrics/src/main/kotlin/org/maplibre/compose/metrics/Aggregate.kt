package org.maplibre.compose.metrics

import kotlin.math.ceil

/** Commits per repository-relative path over a window, from git. */
data class Churn(val windowDays: Long, val since: String, val commitsPerFile: Map<String, Int>)

fun aggregate(
  files: List<FileFacts>,
  apiRoots: List<String>,
  churn: Churn?,
  top: Int,
): Pair<Summary, Sections> {
  val main = files.filter { !it.source.isTest }
  val test = files.filter { it.source.isTest }
  val api = main.filter { file -> apiRoots.any { file.source.relativePath.startsWith("$it/") } }

  val sourceSets = sourceSetReports(files)
  val graph = packageGraph(main)
  val packages = packageReports(main, graph)
  val abstractions = abstractionReports(main, test)
  val distributions = distributions(main)
  val largest = largest(main, packages, top)
  val churnReport = churn?.let { churnReport(it, files, top) }

  val declarations = main.flatMap { it.declarations }
  val apiDeclarations = api.flatMap { it.declarations }
  val publicApi = apiDeclarations.filter {
    it.isEffectivelyPublic && !it.isOverride && !it.isActual
  }
  val mainLoc = main.sumOf { it.loc }
  val testLoc = test.sumOf { it.loc }
  val sloc = main.sumOf { it.sloc }
  val lloc = main.sumOf { it.lloc }
  val cloc = main.sumOf { it.cloc }
  val mcc = main.sumOf { it.cyclomaticComplexity }
  val cycles = graph.cycles
  val packagesInCycles = cycles.flatten().toSet()

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
      functionNestingDepthP90 = dist("functionNestingDepth").p90,
      functionNestingDepthMax = dist("functionNestingDepth").max,
      functionParametersP90 = dist("functionParameters").p90,
      functionParametersMax = dist("functionParameters").max,
      fileLocP90 = dist("fileLoc").p90,
      fileLocMax = dist("fileLoc").max,
      typeLinesP90 = dist("typeLines").p90,
      typeLinesMax = dist("typeLines").max,
      typePublicMembersP90 = dist("typePublicMembers").p90,
      typePublicMembersMax = dist("typePublicMembers").max,
      packageEdges = graph.edges.size,
      packageCycles = cycles.size,
      packagesInCycles = packagesInCycles.size,
      bidirectionalPackagePairs = graph.bidirectionalPairs.size,
      meanPackageInstability = packages.map { it.instability }.average().orZero(),
      packageSourceSetsMean = packages.map { it.sourceSets.size.toDouble() }.average().orZero(),
      packageSourceSetsMax = packages.maxOfOrNull { it.sourceSets.size } ?: 0,
      expectDeclarations = declarations.count { it.isExpect },
      actualDeclarations = declarations.count { it.isActual },
      abstractions = abstractions.count { !it.isSealed },
      abstractionsWithSingleImplementation =
        abstractions.count { !it.isSealed && it.mainImplementationCount() == 1 },
      abstractionsWithNoImplementation =
        abstractions.count {
          !it.isSealed && it.mainImplementationCount() + it.testImplementationCount() == 0
        },
      publicDeclarations = publicApi.size,
      internalDeclarations =
        apiDeclarations.count { it.effectiveVisibility == Visibility.INTERNAL && !it.isActual },
      publicDeclarationsWithKDoc = publicApi.count { it.hasKDoc },
      kdocCoverage = ratio(publicApi.count { it.hasKDoc }, publicApi.size),
      todoComments = files.sumOf { it.todoCount },
      suppressAnnotations = files.sumOf { it.suppressCount },
    )
  return summary to
    Sections(sourceSets, packages, graph, abstractions, distributions, largest, churnReport)
}

data class Sections(
  val sourceSets: List<SourceSetReport>,
  val packages: List<PackageReport>,
  val packageGraph: PackageGraph,
  val abstractions: List<AbstractionReport>,
  val distributions: Map<String, Distribution>,
  val largest: Largest,
  val churn: ChurnReport?,
)

private fun sourceSetReports(files: List<FileFacts>): List<SourceSetReport> =
  files
    .groupBy { it.source.module to it.source.sourceSet }
    .map { (key, group) ->
      val declarations = group.flatMap { it.declarations }
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
        publicDeclarations =
          declarations.count { it.isEffectivelyPublic && !it.isOverride && !it.isActual },
        internalDeclarations =
          declarations.count { it.effectiveVisibility == Visibility.INTERNAL && !it.isActual },
        expectDeclarations = declarations.count { it.isExpect },
        actualDeclarations = declarations.count { it.isActual },
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
        .flatMap { file ->
          (file.types.map { it.name } + file.typeAliases.map { it.name }).map {
            file.qualify(it) to file.packageName
          }
        }
        .toMap(),
  )

  /** The scanned package [import] refers to, or null when it is external. */
  fun packageOf(import: Import): String? {
    val qualifier =
      if (import.allUnder) import.fqName else import.fqName.substringBeforeLast('.', "")
    return if (qualifier in packages) qualifier else typePackages[qualifier]
  }
}

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
      val declarations = group.flatMap { it.declarations }
      val ce = dependsOn[name].orEmpty().sorted()
      val ca = dependedOnBy[name].orEmpty().sorted()
      PackageReport(
        name = name,
        files = group.size,
        loc = group.sumOf { it.loc },
        types = group.sumOf { it.types.size },
        functions = group.sumOf { it.functions.size },
        publicDeclarations =
          declarations.count { it.isEffectivelyPublic && !it.isOverride && !it.isActual },
        internalDeclarations =
          declarations.count { it.effectiveVisibility == Visibility.INTERNAL && !it.isActual },
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

/** A type name written in [file], seen [weight] times. */
private data class Reference(val reference: TypeReference, val file: FileFacts, val weight: Int = 1)

private fun AbstractionReport.mainImplementationCount() =
  mainImplementations + mainAnonymousImplementations + mainSamImplementations

private fun AbstractionReport.testImplementationCount() =
  testImplementations + testAnonymousImplementations + testSamImplementations

private fun abstractionReports(
  main: List<FileFacts>,
  test: List<FileFacts>,
): List<AbstractionReport> {
  val index = TypeIndex(main + test)
  val ambiguous = mutableSetOf<String>()

  /** Implementation counts per resolved abstraction. An ambiguous reference counts for each. */
  fun count(references: List<Reference>): Map<String, Int> {
    val counts = mutableMapOf<String, Int>()
    for ((reference, file, weight) in references) {
      when (val resolution = index.resolve(reference, file)) {
        is Resolution.Resolved -> counts.merge(resolution.fqName, weight, Int::plus)
        is Resolution.Ambiguous -> {
          ambiguous += resolution.candidates
          resolution.candidates.forEach { counts.merge(it, weight, Int::plus) }
        }
        Resolution.External -> {}
      }
    }
    return counts
  }
  fun named(files: List<FileFacts>) =
    count(
      files.flatMap { file ->
        file.types.flatMap { type ->
          type.supertypes.map { Reference(TypeReference(it, type.enclosingType), file) }
        }
      }
    )
  fun anonymous(files: List<FileFacts>) =
    count(files.flatMap { file -> file.anonymousImplementations.map { Reference(it, file) } })
  fun calls(files: List<FileFacts>) =
    count(
      files.flatMap { file -> file.calls.map { (reference, n) -> Reference(reference, file, n) } }
    )

  val mainNamed = named(main)
  val mainAnonymous = anonymous(main)
  val mainCalls = calls(main)
  val testNamed = named(test)
  val testAnonymous = anonymous(test)
  val testCalls = calls(test)

  return main
    .flatMap { file ->
      file.types
        .filter { it.kind == TypeKind.INTERFACE || (it.kind == TypeKind.CLASS && it.isAbstract) }
        .filter { !it.isActual && !it.isExternal }
        .map { file to it }
    }
    .map { (file, type) ->
      val fqName = file.qualify(type.name)
      AbstractionReport(
        name = type.name,
        packageName = file.packageName,
        kind = type.kind.name.lowercase(),
        isSealed = type.isSealed,
        isFunInterface = type.isFunInterface,
        isEffectivelyPublic = type.isEffectivelyPublic,
        mainImplementations = mainNamed[fqName] ?: 0,
        mainAnonymousImplementations = mainAnonymous[fqName] ?: 0,
        mainSamImplementations = if (type.isFunInterface) mainCalls[fqName] ?: 0 else 0,
        testImplementations = testNamed[fqName] ?: 0,
        testAnonymousImplementations = testAnonymous[fqName] ?: 0,
        testSamImplementations = if (type.isFunInterface) testCalls[fqName] ?: 0 else 0,
        ambiguous = fqName in ambiguous,
      )
    }
    .sortedWith(compareBy({ it.packageName }, { it.name }))
}

private fun distributions(main: List<FileFacts>): Map<String, Distribution> {
  val functions = main.flatMap { file -> file.functions.map { file.source.relativePath to it } }
  val types = main.flatMap { file -> file.types.map { file.source.relativePath to it } }
  fun named(name: String, value: Int) = Ranked(name, value)
  return linkedMapOf(
    "fileLoc" to distribution(main.map { named(it.source.relativePath, it.loc) }),
    "typeLines" to distribution(types.map { (path, t) -> named("$path:${t.name}", t.lines) }),
    "typePublicMembers" to
      distribution(types.map { (path, t) -> named("$path:${t.name}", t.publicMembers) }),
    "functionLines" to
      distribution(functions.map { (path, f) -> named("$path:${f.line}:${f.name}", f.lines) }),
    "functionCyclomaticComplexity" to
      distribution(
        functions.map { (path, f) -> named("$path:${f.line}:${f.name}", f.cyclomaticComplexity) }
      ),
    "functionCognitiveComplexity" to
      distribution(
        functions.map { (path, f) -> named("$path:${f.line}:${f.name}", f.cognitiveComplexity) }
      ),
    "functionNestingDepth" to
      distribution(
        functions.map { (path, f) -> named("$path:${f.line}:${f.name}", f.nestingDepth) }
      ),
    "functionParameters" to
      distribution(functions.map { (path, f) -> named("$path:${f.line}:${f.name}", f.parameters) }),
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

private fun largest(main: List<FileFacts>, packages: List<PackageReport>, top: Int): Largest {
  fun List<Ranked>.top() = sortedByDescending { it.value }.take(top)
  val types = main.flatMap { file -> file.types.map { file.source.relativePath to it } }
  val functions = main.flatMap { file -> file.functions.map { file.source.relativePath to it } }
  return Largest(
    filesByLoc = main.map { Ranked(it.source.relativePath, it.loc) }.top(),
    typesByLines = types.map { (path, t) -> Ranked("$path:${t.name}", t.lines) }.top(),
    typesByPublicMembers =
      types.map { (path, t) -> Ranked("$path:${t.name}", t.publicMembers) }.top(),
    functionsByLines =
      functions.map { (path, f) -> Ranked("$path:${f.line}:${f.name}", f.lines) }.top(),
    functionsByCognitiveComplexity =
      functions
        .map { (path, f) -> Ranked("$path:${f.line}:${f.name}", f.cognitiveComplexity) }
        .top(),
    packagesByTypes = packages.map { Ranked(it.name, it.types) }.top(),
    packagesByPublicDeclarations = packages.map { Ranked(it.name, it.publicDeclarations) }.top(),
  )
}

private fun churnReport(churn: Churn, files: List<FileFacts>, top: Int): ChurnReport {
  val loc = files.associate { it.source.relativePath to it.loc }
  val kotlin = churn.commitsPerFile.filterKeys { it.endsWith(".kt") }
  return ChurnReport(
    windowDays = churn.windowDays,
    since = churn.since,
    fileTouches = kotlin.values.sum(),
    filesTouched = kotlin.size,
    mostChanged =
      kotlin.map { (path, n) -> Ranked(path, n) }.sortedByDescending { it.value }.take(top),
    hotspots =
      kotlin
        .mapNotNull { (path, n) -> loc[path]?.let { Ranked(path, n * it) } }
        .sortedByDescending { it.value }
        .take(top),
  )
}

private fun ratio(numerator: Int, denominator: Int): Double =
  if (denominator == 0) 0.0 else numerator.toDouble() / denominator

private fun Double.orZero(): Double = if (isNaN()) 0.0 else this
