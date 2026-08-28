package org.maplibre.compose.map

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.Dp
import kotlin.time.Duration

/**
 * The platform strategy behind a [MapState]'s lifetime: where the platform allows it, the engine
 * owns the map beyond any one composition, and the composition only attaches render sessions.
 */
internal expect class MapEngine(state: MapState) : AutoCloseable {
  /** The live map that outlives the composition, or null where the platform keeps none. */
  val detachedAdapter: MapAdapter?

  /** Backs [MapState.captureStillImage]. */
  suspend fun captureStillImage(width: Dp, height: Dp, timeout: Duration): ImageBitmap

  override fun close()
}
