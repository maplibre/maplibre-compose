package org.maplibre.compose.map

import org.maplibre.compose.style.BaseStyle

/**
 * The platform strategy behind a [MapState]'s lifetime: where the platform allows it, the engine
 * owns the map beyond any one composition, and the composition only attaches render sessions.
 */
internal interface MapEngine : AutoCloseable {
  /** Whether a session detach leaves the loaded style and its applied content in place. */
  val retainsStyleAcrossDetach: Boolean

  /** Records the selected base style and carries it to a map the engine owns while detached. */
  fun setBaseStyle(style: BaseStyle)
}

/** Creates the engine that gives [state] its platform lifetime. */
internal expect fun createMapEngine(state: MapState): MapEngine
