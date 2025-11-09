package org.maplibre.compose.sources

import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.style.sources.CustomGeometrySource
import org.maplibre.android.style.sources.CustomGeometrySourceOptions
import org.maplibre.android.style.sources.GeometryTileProvider
import org.maplibre.compose.util.toBoundingBox
import org.maplibre.compose.util.toLatLngBounds
import org.maplibre.geojson.FeatureCollection as MLNFeatureCollection
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.GeoJsonObject
import org.maplibre.spatialk.geojson.toJson

public actual class ComputedSource : Source {
  override val impl: CustomGeometrySource

  internal constructor(impl: CustomGeometrySource) {
    this.impl = impl
  }

  public actual constructor(
    id: String,
    options: ComputedSourceOptions,
    getFeatures: (bounds: BoundingBox, zoomLevel: Int) -> FeatureCollection<*, *>,
  ) : this(
    CustomGeometrySource(
      id = id,
      options = buildOptionMap(options),
      provider =
        object : GeometryTileProvider {
          override fun getFeaturesForBounds(
            bounds: LatLngBounds,
            zoomLevel: Int,
          ): MLNFeatureCollection {
            // HACK: we intentionally drop the FeatureCollection<*, *> type info in order to use the
            // runtime serializer detection of GeoJsonObject.
            val features: GeoJsonObject = getFeatures(bounds.toBoundingBox(), zoomLevel)
            return MLNFeatureCollection.fromJson(features.toJson())
          }
        },
    )
  )

  public actual fun invalidateBounds(bounds: BoundingBox) {
    impl.invalidateRegion(bounds.toLatLngBounds())
  }

  public actual fun invalidateTile(zoomLevel: Int, x: Int, y: Int) {
    impl.invalidateTile(zoomLevel = zoomLevel, x = x, y = y)
  }

  public actual fun setData(zoomLevel: Int, x: Int, y: Int, data: FeatureCollection<*, *>) {
    impl.setTileData(
      zoomLevel = zoomLevel,
      x = x,
      y = y,
      data = MLNFeatureCollection.fromJson(data.toJson()),
    )
  }

  private companion object {
    private fun buildOptionMap(options: ComputedSourceOptions) =
      CustomGeometrySourceOptions().apply {
        withMinZoom(options.minZoom)
        withMaxZoom(options.maxZoom)
        withBuffer(options.buffer)
        withTolerance(options.tolerance)
        withClip(options.clip)
        withWrap(options.wrap)
      }
  }
}
