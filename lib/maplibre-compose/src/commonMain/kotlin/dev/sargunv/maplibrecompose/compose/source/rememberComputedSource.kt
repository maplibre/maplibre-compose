package dev.sargunv.maplibrecompose.compose.source

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import dev.sargunv.maplibrecompose.core.source.ComputedSource
import dev.sargunv.maplibrecompose.core.source.ComputedSourceOptions
import io.github.dellisd.spatialk.geojson.BoundingBox
import io.github.dellisd.spatialk.geojson.FeatureCollection

/**
 * Remember a new [ComputedSource] with the given [options] from the given [getFeatures] function.
 */
@Composable
public fun rememberGeoJsonSource(
  options: ComputedSourceOptions = ComputedSourceOptions(),
  getFeatures: (bounds: BoundingBox, zoomLevel: Int) -> FeatureCollection,
): ComputedSource =
  key(options, getFeatures) {
    rememberUserSource(
      factory = { ComputedSource(id = it, options = options, getFeatures = getFeatures) },
      update = {},
    )
  }
