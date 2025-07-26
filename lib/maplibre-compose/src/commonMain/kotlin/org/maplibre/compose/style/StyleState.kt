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

  internal fun attach(styleNode: StyleNode?) {
    if (this.styleNode != styleNode) {
      this.styleNode = styleNode
      styleNode?.sourceManager?.state = this
      reloadSources()
    }
  }

  internal fun reloadSources() {
    this.sourcesState.value = styleNode?.style?.getSources().orEmpty().associateBy { it.id }
  }
}
