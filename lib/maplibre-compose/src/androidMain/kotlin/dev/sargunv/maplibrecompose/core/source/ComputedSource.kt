package dev.sargunv.maplibrecompose.core.source

import dev.sargunv.maplibrecompose.core.util.toBoundingBox
import dev.sargunv.maplibrecompose.core.util.toLatLngBounds
import io.github.dellisd.spatialk.geojson.BoundingBox
import io.github.dellisd.spatialk.geojson.FeatureCollection
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.style.sources.CustomGeometrySource
import org.maplibre.android.style.sources.CustomGeometrySourceOptions
import org.maplibre.android.style.sources.GeometryTileProvider
import org.maplibre.geojson.FeatureCollection as MLNFeatureCollection

public actual class ComputedSource : Source {
  override val impl: CustomGeometrySource

  internal constructor(impl: CustomGeometrySource) {
    this.impl = impl
  }

  public actual constructor(
    id: String,
    options: ComputedSourceOptions,
    getFeatures: (BoundingBox, Int) -> FeatureCollection,
  ) : this(
    CustomGeometrySource(
      id = id,
      options = buildOptionMap(options),
      provider =
        object : GeometryTileProvider {
          override fun getFeaturesForBounds(
            bounds: LatLngBounds,
            zoomLevel: Int,
          ): MLNFeatureCollection =
            MLNFeatureCollection.fromJson(getFeatures(bounds.toBoundingBox(), zoomLevel).json())
        },
    )
  )

  public actual fun invalidateBounds(bounds: BoundingBox) {
    impl.invalidateRegion(bounds.toLatLngBounds())
  }

  public actual fun invalidateTile(zoomLevel: Int, x: Int, y: Int) {
    impl.invalidateTile(zoomLevel = zoomLevel, x = x, y = y)
  }

  public actual fun setData(zoomLevel: Int, x: Int, y: Int, data: FeatureCollection) {
    impl.setTileData(
      zoomLevel = zoomLevel,
      x = x,
      y = y,
      data = MLNFeatureCollection.fromJson(data.json()),
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
