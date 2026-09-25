package org.maplibre.compose.demoapp.demos

import androidx.compose.runtime.Composable
import org.maplibre.compose.util.MaplibreComposable

/**
 * The hexagonal-bin rendering of the earthquake feed in [DataVizDemo]. The bins are hexagons that
 * MapLibre Native's n-gon layer plugin draws at each cell center, so this exists only where the
 * demo can load that plugin.
 */
internal interface EarthquakeHexbins {
  /** Bins the feed at [feedUri] for the current zoom and draws the cells. */
  @Composable @MaplibreComposable fun MapContent(feedUri: String)
}

/** The hexbin rendering, or null where the n-gon layer plugin is unavailable. */
internal expect fun earthquakeHexbins(): EarthquakeHexbins?
