package org.maplibre.compose.metrics

/**
 * Reports for all code, the library, the demo app, and each module, each recomputed from its files.
 */
fun scopedReports(files: List<FileFacts>, top: Int): List<ScopeReport> = buildList {
  fun report(group: String, module: String?, subset: List<FileFacts>) {
    val (summary, sections) = aggregate(subset, top)
    add(
      ScopeReport(
        group,
        module,
        summary,
        sections.sourceSets,
        sections.packages,
        sections.packageGraph,
        sections.distributions,
        sections.largest,
      )
    )
  }
  report("all", null, files)
  for ((group, prefix) in listOf("library" to "lib/", "demo" to "demo-app/")) {
    val subset = files.filter { it.source.relativePath.startsWith(prefix) }
    if (subset.isEmpty()) continue
    report(group, null, subset)
    subset
      .groupBy { it.source.module }
      .toSortedMap()
      .forEach { (module, moduleFiles) ->
        report(group, module, moduleFiles)
      }
  }
}
