package org.maplibre.compose.style.source

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import org.maplibre.compose.core.source.GeoJsonData
import org.maplibre.compose.core.source.GeoJsonOptions
import org.maplibre.compose.core.source.GeoJsonSource

/** Remember a new [GeoJsonSource] with the given [options] from the given [GeoJsonData]. */
@Composable
public fun rememberGeoJsonSource(
  data: GeoJsonData,
  options: GeoJsonOptions = GeoJsonOptions(),
): GeoJsonSource =
  key(options) {
    rememberUserSource(
      factory = { GeoJsonSource(id = it, data = data, options = options) },
      update = { setData(data) },
    )
  }
