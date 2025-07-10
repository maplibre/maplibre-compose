package dev.sargunv.maplibrecompose.compose.source

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import dev.sargunv.maplibrecompose.core.source.GeoJsonData
import dev.sargunv.maplibrecompose.core.source.GeoJsonOptions
import dev.sargunv.maplibrecompose.core.source.GeoJsonSource

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
