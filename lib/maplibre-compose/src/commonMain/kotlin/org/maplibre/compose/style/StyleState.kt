package org.maplibre.compose.style

import androidx.compose.runtime.mutableStateOf
import org.maplibre.compose.sources.Source

/**
 * The snapshot-backed source map of a [MapState][org.maplibre.compose.map.MapState], refreshed as
 * the live style reports source changes. The public read path is
 * [StyleSources][org.maplibre.compose.map.StyleSources].
 */
internal class StyleState {
  private var styleNode: StyleNode? = null

  val sources: Map<String, Source>
    get() = sourcesState.value

  private val sourcesState = mutableStateOf(emptyMap<String, Source>())

  internal fun attach(styleNode: StyleNode) {
    this.styleNode?.sourceManager?.state = null
    this.styleNode = styleNode
    styleNode.sourceManager.state = this
    sourcesState.value = styleNode.binding.getSources().associateBy { it.id }
  }

  /** The inverse of [attach]: a detached state stops reporting a dead map's sources. */
  internal fun detach() {
    styleNode?.sourceManager?.state = null
    styleNode = null
    if (sourcesState.value.isNotEmpty()) sourcesState.value = emptyMap()
  }

  internal fun refreshSource(id: String) {
    val node = styleNode ?: return
    if (!node.binding.isLoaded) return

    val current = sourcesState.value
    val refreshed = node.binding.getSource(id)
    val previous = current[id]
    when {
      refreshed == null && previous != null -> sourcesState.value = current - id
      refreshed != null && !refreshed.hasSameState(previous) ->
        sourcesState.value = current + (id to refreshed)
    }
  }

  internal fun refreshSources() {
    val node = styleNode
    if (node == null) {
      if (sourcesState.value.isNotEmpty()) sourcesState.value = emptyMap()
      return
    }
    if (!node.binding.isLoaded) return

    val current = sourcesState.value
    val refreshed = node.binding.getSources().associateBy { it.id }
    var changed = current.keys.toList() != refreshed.keys.toList()
    val reconciled = refreshed.mapValues { (id, source) ->
      current[id]?.takeIf { source.hasSameState(it) } ?: source.also { changed = true }
    }
    if (changed) sourcesState.value = reconciled
  }

  private fun Source.hasSameState(other: Source?): Boolean =
    other != null && this::class == other::class && attributionHtml == other.attributionHtml
}
