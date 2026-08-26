package org.maplibre.compose.style

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import org.maplibre.compose.sources.Source

/** Remember a new [StyleState]. */
@Composable
public fun rememberStyleState(): StyleState {
  return remember { StyleState() }
}

/** Use this class to access information about the style, such as sources and layers. */
public class StyleState internal constructor() {
  private var styleNode: StyleNode? = null

  public val sources: Map<String, Source>
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
