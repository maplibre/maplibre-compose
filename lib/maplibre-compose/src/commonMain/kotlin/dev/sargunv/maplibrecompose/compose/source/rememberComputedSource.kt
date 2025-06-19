package dev.sargunv.maplibrecompose.compose.source

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import dev.sargunv.maplibrecompose.core.source.ComputedSource
import dev.sargunv.maplibrecompose.core.source.ComputedSourceOptions
import io.github.dellisd.spatialk.geojson.BoundingBox
import io.github.dellisd.spatialk.geojson.FeatureCollection

/**
 * Remember a new [ComputedSource] with the given [id] and [options] from the given [getFeatures]
 * function.
 */
@Composable
public fun rememberGeoJsonSource(
  id: String,
  options: ComputedSourceOptions = ComputedSourceOptions(),
  getFeatures: (bounds: BoundingBox, zoomLevel: Int) -> FeatureCollection,
): ComputedSource =
  key(id, options, getFeatures) {
    rememberUserSource(
      factory = { ComputedSource(id = id, options = options, getFeatures = getFeatures) },
      update = {},
    )
  }
