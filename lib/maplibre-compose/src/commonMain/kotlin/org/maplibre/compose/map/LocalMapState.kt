package org.maplibre.compose.map

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * The [MapState] of the enclosing map.
 *
 * The style composition and the overlay of a [MaplibreMap] both provide this local, so a composable
 * in either place reads the map it composes into without a parameter. Reading the local outside
 * those compositions throws [IllegalStateException].
 */
public val LocalMapState: ProvidableCompositionLocal<MapState> = staticCompositionLocalOf {
  error(
    "No MapState is provided here. LocalMapState is available inside a map's style composition and " +
      "inside a map's overlay."
  )
}
