package org.maplibre.compose.style

import org.maplibre.compose.sources.Source

internal class SourceManager(private val node: StyleNode) {

  private val baseSources =
    node.style.getSources().filterNot { it.id in node.replaceableSourceIds }.associateBy { it.id }
  private val counter = ReferenceCounter<Source>()
  private val sourceIds = IncrementingId("source")

  /** Application-owned sources in the order in which the evaluator first referenced them. */
  internal val desiredSources = LinkedHashSet<Source>()

  /** Receives updates on changes to the style */
  internal var state: StyleState? = null

  internal fun getBaseSource(id: String): Source? {
    return baseSources[id]
  }

  internal fun nextId(): String = sourceIds.next()

  internal fun addReference(source: Source) {
    require(source.id !in baseSources) { "Source ID '${source.id}' already exists in base style" }
    counter.increment(source) {
      desiredSources += source
      node.scheduleApplyChanges()
    }
  }

  internal fun removeReference(source: Source) {
    require(source.id !in baseSources) {
      "Source ID '${source.id}' is part of the base style and can't be removed here"
    }
    counter.decrement(source) {
      desiredSources -= source
      node.scheduleApplyChanges()
    }
  }

  internal suspend fun updateReference(source: Source) {
    if (source in desiredSources) node.scheduleApplyChanges()
  }
}
