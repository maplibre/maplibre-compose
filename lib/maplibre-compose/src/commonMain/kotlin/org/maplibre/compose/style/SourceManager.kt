package org.maplibre.compose.style

import org.maplibre.compose.sources.Source

internal class SourceManager(private val node: StyleNode) {

  private val baseSources = node.style.getSources().associateBy { it.id }
  private val counter = ReferenceCounter<Source>()
  private val handles = mutableMapOf<Source, SourceHandle>()
  private val sourceIds = IncrementingId("source")

  /** Receives updates on changes to the style */
  internal var state: StyleState? = null

  internal fun getBaseSource(id: String): Source? {
    return baseSources[id]
  }

  internal fun nextId(): String = sourceIds.next()

  internal fun addReference(source: Source) {
    require(source.id !in baseSources) { "Source ID '${source.id}' already exists in base style" }
    counter.increment(source) {
      if (!node.style.isLoaded) return@increment
      node.logger?.i { "Adding source ${source.id}" }
      handles[source] = SourceHandle(node.style, source.definition())
      state?.refreshSource(source.id)
    }
  }

  internal fun removeReference(source: Source) {
    require(source.id !in baseSources) {
      "Source ID '${source.id}' is part of the base style and can't be removed here"
    }
    counter.decrement(source) {
      if (!node.style.isLoaded) return@decrement
      node.logger?.i { "Removing source ${source.id}" }
      handles.remove(source)?.remove()
      state?.refreshSource(source.id)
    }
  }

  internal suspend fun updateReference(source: Source) {
    if (!node.style.isLoaded) return
    handles[source]?.update(source.definition())
  }
}
