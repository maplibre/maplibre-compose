package org.maplibre.compose.map

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import org.maplibre.compose.camera.Viewport

/**
 * The viewport that the style content is evaluated for.
 *
 * An interactive map provides [MapState.viewport], which is null until the map has rendered a
 * frame. A snapshotter provides the viewport of the capture request it is evaluating for. A
 * composition that reads this value recomposes when the viewport changes.
 */
public val LocalViewport: ProvidableCompositionLocal<Viewport?> = compositionLocalOf { null }

/**
 * The interactive map whose style content is being evaluated.
 *
 * Only an interactive map provides this value. Reading it from the content of a [MapSnapshotter]
 * throws [IllegalStateException].
 */
public val LocalMapState: ProvidableCompositionLocal<MapState> = staticCompositionLocalOf {
  throw IllegalStateException(
    "LocalMapState is available only in the style content of an interactive map. " +
      "A snapshotter has no MapState."
  )
}
