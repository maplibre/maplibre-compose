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
    private set

  private var closed = false

  /** Retains [session] as the composed session; a closed engine closes it instead. */
  internal fun registerSession(session: GlJsMapSession) {
    if (closed) {
      // A still-composed view can register a fresh session after close; retaining it would leak a
      // live map no close path reaches again.
      session.logger?.w { "Closing a map session composed against a closed MapState" }
      session.close()
      return
    }
    this.session = session
  }

  /** Forgets [session] once its composable leaves, keeping a replacement registration intact. */
  internal fun releaseSession(session: GlJsMapSession) {
    if (this.session === session) this.session = null
  }

  override val retainsStyleAcrossDetach: Boolean
    get() = false

  override fun setBaseStyle(style: BaseStyle) {
    // There is no map to receive it while detached; the state re-pushes the style at attach.
  }

  override fun close() {
    closed = true
    session?.close()
    session = null
  }
}

internal actual fun createMapEngine(state: MapState): MapEngine = GlJsMapEngine()
