package org.maplibre.compose.map

import org.maplibre.compose.style.BaseStyle

/**
 * A thin holder: MapLibre GL JS fuses the map with its WebGL context, so the live map exists only
 * while a session is composed, and [MapState] replays the recorded style and camera into each new
 * session at attach. The engine tracks the live session so closing the state closes a map that is
 * still composed.
 */
internal class GlJsMapEngine : MapEngine {

  /** The composed session, or null while no [MaplibreMap] shows this state. */
  internal var session: GlJsMapSession? = null

  override val retainsStyleAcrossDetach: Boolean
    get() = false

  override fun setBaseStyle(style: BaseStyle) {
    // There is no map to receive it while detached; the state re-pushes the style at attach.
  }

  override fun close() {
    session?.close()
    session = null
  }
}

internal actual fun createMapEngine(state: MapState): MapEngine = GlJsMapEngine()
