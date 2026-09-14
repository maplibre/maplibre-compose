package org.maplibre.compose.map

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import org.maplibre.compose.camera.Viewport

/**
 * The viewport of the enclosing map overlay or style content.
 *
 * An interactive map provides [MapState.viewport], which is null until the map has rendered a
 * frame. A snapshotter provides the viewport of the capture request it is evaluating for. A
 * composition that reads this value recomposes when the viewport changes.
 */
public val LocalViewport: ProvidableCompositionLocal<Viewport?> = compositionLocalOf { null }

/**
 * The interactive map enclosing the overlay or style content, or null outside a map and in the
 * style content of a [MapSnapshotter].
 */
public val LocalMapState: ProvidableCompositionLocal<MapState?> = staticCompositionLocalOf { null }
