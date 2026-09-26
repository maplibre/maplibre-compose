package org.maplibre.compose.metrics

import kotlinx.serialization.Serializable

/**
 * One snapshot of the codebase. [summary] is flat so a trend can graph any of its fields; the other
 * sections hold the detail behind each number. Everything is measured from syntax alone.
 */
@Serializable
data class Snapshot(
  val schemaVersion: Int = 1,
  val generatedAt: String,
  val ref: String?,
  val commit: String?,
  val commitDate: String?,
  val describe: String?,
  val dirty: Boolean?,
  val roots: List<String>,
  val summary: Summary,
  val sourceSets: List<SourceSetReport>,
  val packages: List<PackageReport>,
  val packageGraph: PackageGraph,
  val distributions: Map<String, Distribution>,
  val largest: Largest,
  val scopes: List<ScopeReport> = emptyList(),
)

/** A filtered report, recomputed from the files in this scope, including its percentiles. */
@Serializable
data class ScopeReport(
  val group: String,
  val module: String?,
  val sourceSet: String?,
  val summary: Summary,
  val sourceSets: List<SourceSetReport>,
  val packages: List<PackageReport>,
  val packageGraph: PackageGraph,
  val distributions: Map<String, Distribution>,
  val largest: Largest,
)

@Serializable
data class Summary(
  // Size. Main source sets only, unless the name says otherwise.
  val files: Int,
  val loc: Int,
  val sloc: Int,
  val lloc: Int,
  val cloc: Int,
  val testLoc: Int,
  val testToMainLocRatio: Double,
  val commentToSourceRatio: Double,
  val packages: Int,
  val types: Int,
  val functions: Int,
  // Complexity.
  val cyclomaticComplexity: Int,
  val cognitiveComplexity: Int,
  val cyclomaticPer1000Lloc: Double,
  val functionLinesP90: Int,
  val functionLinesMax: Int,
  val functionCyclomaticP90: Int,
  val functionCyclomaticMax: Int,
  val functionCognitiveP90: Int,
  val functionCognitiveMax: Int,
  val functionParametersP90: Int,
  val functionParametersMax: Int,
  val fileLocP90: Int,
  val fileLocMax: Int,
  val typeLinesP90: Int,
  val typeLinesMax: Int,
  // Structure.
  val packageEdges: Int,
  val packageCycles: Int,
  val packagesInCycles: Int,
  val bidirectionalPackagePairs: Int,
  val meanPackageInstability: Double,
  val packageSourceSetsMean: Double,
  val packageSourceSetsMax: Int,
  val expectDeclarations: Int,
  val actualDeclarations: Int,
  // Hygiene, over main and test code.
  val todoComments: Int,
  val suppressAnnotations: Int,
)

@Serializable
data class SourceSetReport(
  val module: String,
  val name: String,
  val isTest: Boolean,
  val files: Int,
  val loc: Int,
  val sloc: Int,
  val lloc: Int,
  val cloc: Int,
  val cyclomaticComplexity: Int,
  val cognitiveComplexity: Int,
  val types: Int,
  val functions: Int,
  val expectDeclarations: Int,
  val actualDeclarations: Int,
)

@Serializable
data class PackageReport(
  val name: String,
  val files: Int,
  val loc: Int,
  val types: Int,
  val functions: Int,
  val sourceSets: List<String>,
  /** Packages this one imports (efferent coupling, Ce). */
  val dependsOn: List<String>,
  /** Packages importing this one (afferent coupling, Ca). */
  val dependedOnBy: List<String>,
  /** Ce / (Ca + Ce): 0 is fully stable, 1 fully unstable. */
  val instability: Double,
  /** Distinct imports of names outside the scanned code. */
  val externalImports: Int,
)

@Serializable data class PackageEdge(val from: String, val to: String, val imports: Int)

@Serializable
data class PackageGraph(
  val edges: List<PackageEdge>,
  /** Strongly connected components with more than one package. */
  val cycles: List<List<String>>,
  val bidirectionalPairs: List<List<String>>,
)

@Serializable
data class Distribution(
  val count: Int,
  val mean: Double,
  val p50: Int,
  val p90: Int,
  val p99: Int,
  val max: Int,
  val maxName: String?,
  val p75: Int = 0,
  /** Exact value frequencies; their sum is [count]. */
  val histogram: Map<Int, Int> = emptyMap(),
)

@Serializable data class Ranked(val name: String, val value: Int)

@Serializable
data class Largest(
  val filesByLoc: List<Ranked>,
  val typesByLines: List<Ranked>,
  val functionsByLines: List<Ranked>,
  val functionsByCognitiveComplexity: List<Ranked>,
  val packagesByTypes: List<Ranked>,
)
