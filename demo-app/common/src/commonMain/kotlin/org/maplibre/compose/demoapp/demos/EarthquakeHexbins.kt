package org.maplibre.compose.demoapp.demos

import androidx.compose.runtime.Composable
import org.maplibre.compose.util.MaplibreComposable

/** [DataVizDemo]'s hexagonal bins, drawn by MapLibre Native's n-gon layer plugin. */
internal interface EarthquakeHexbins {
  @Composable @MaplibreComposable fun MapContent(feedUri: String)
}

/** Null where the plugin cannot be loaded. */
internal expect fun earthquakeHexbins(): EarthquakeHexbins?
