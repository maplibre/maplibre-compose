package org.maplibre.compose.metrics

/** Source-set filters use production sets. Tests remain available in unfiltered scopes. */
fun scopedReports(files: List<FileFacts>, top: Int): List<ScopeReport> = buildList {
  fun report(group: String, module: String?, sourceSet: String?, subset: List<FileFacts>) {
    val (summary, sections) = aggregate(subset, top)
    add(
      ScopeReport(
        group,
        module,
        sourceSet,
        summary,
        sections.sourceSets,
        sections.packages,
        sections.packageGraph,
        sections.distributions,
        sections.largest,
      )
    )
  }
  fun sourceSets(group: String, module: String?, subset: List<FileFacts>) {
    report(group, module, null, subset)
    subset
      .filter { !it.source.isTest }
      .groupBy { it.source.sourceSet }
      .toSortedMap()
      .forEach { (name, setFiles) -> report(group, module, name, setFiles) }
  }
  sourceSets("all", null, files)
  for ((group, prefix) in listOf("library" to "lib/", "demo" to "demo-app/")) {
    val subset = files.filter { it.source.relativePath.startsWith(prefix) }
    if (subset.isEmpty()) continue
    sourceSets(group, null, subset)
    subset
      .groupBy { it.source.module }
      .toSortedMap()
      .forEach { (module, moduleFiles) ->
        sourceSets(group, module, moduleFiles)
      }
  }
}
