package org.maplibre.compose.map

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.Dp
import kotlin.time.Duration

/**
 * A thin holder: MapLibre GL JS fuses the map with its WebGL context, so the live map exists only
 * while a session is composed, and [MapState] replays the recorded style and camera into each new
 * session at attach. The engine tracks the live session so closing the state closes a map that is
 * still composed.
 */
internal actual class MapEngine actual constructor(@Suppress("unused") state: MapState) :
  AutoCloseable {

  /** No map outlives the composition here, so the state replays the style into the next one. */
  actual val detachedAdapter: MapAdapter?
    get() = null

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
    val current = this.session
    check(current == null || current === session) { SINGLE_SESSION_ERROR }
    this.session = session
  }

  /** Forgets [session] once its composable leaves, keeping a replacement registration intact. */
  internal fun releaseSession(session: GlJsMapSession) {
    if (this.session === session) this.session = null
  }

  actual suspend fun captureStillImage(
    width: Dp,
    height: Dp,
    timeout: Duration,
    @Suppress("UNUSED_PARAMETER") capture: RendererState.Capture,
  ): ImageBitmap {
    throw UnsupportedOperationException(
      "MapLibre GL JS has no still-image API; MapState.captureStillImage is unavailable in the " +
        "browser"
    )
  }

  actual override fun close() {
    closed = true
    session?.closingWithState = true
    session?.close()
    session = null
  }
}
