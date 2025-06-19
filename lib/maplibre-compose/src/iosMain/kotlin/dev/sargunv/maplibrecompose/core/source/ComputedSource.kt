package dev.sargunv.maplibrecompose.core.source

import MapLibre.MLNComputedShapeSource
import MapLibre.MLNComputedShapeSourceDataSourceProtocol
import MapLibre.MLNCoordinateBounds
import MapLibre.MLNShape
import MapLibre.MLNShapeSourceOptionBuffer
import MapLibre.MLNShapeSourceOptionClipsCoordinates
import MapLibre.MLNShapeSourceOptionMaximumZoomLevel
import MapLibre.MLNShapeSourceOptionMinimumZoomLevel
import MapLibre.MLNShapeSourceOptionSimplificationTolerance
import MapLibre.MLNShapeSourceOptionWrapsCoordinates
import dev.sargunv.maplibrecompose.core.util.toBoundingBox
import dev.sargunv.maplibrecompose.core.util.toMLNCoordinateBounds
import dev.sargunv.maplibrecompose.core.util.toMLNShape
import io.github.dellisd.spatialk.geojson.BoundingBox
import io.github.dellisd.spatialk.geojson.FeatureCollection
import kotlinx.cinterop.CValue
import platform.Foundation.NSNumber
import platform.darwin.NSObject
import platform.darwin.NSUInteger

public actual class ComputedSource : Source {
  override val impl: MLNComputedShapeSource

  internal constructor(impl: MLNComputedShapeSource) {
    this.impl = impl
  }

  public actual constructor(
    id: String,
    options: ComputedSourceOptions,
    getFeatures: (BoundingBox, Int) -> FeatureCollection,
  ) : this(
    MLNComputedShapeSource(
      identifier = id,
      options = buildOptionMap(options),
      dataSource =
        object : MLNComputedShapeSourceDataSourceProtocol, NSObject() {
          override fun featuresInCoordinateBounds(
            bounds: CValue<MLNCoordinateBounds>,
            zoomLevel: NSUInteger,
          ): List<MLNShape> =
            getFeatures(bounds.toBoundingBox(), zoomLevel.toInt()).map { it.toMLNShape() }
        },
    )
  )

  public actual fun invalidateBounds(bounds: BoundingBox) {
    impl.invalidateBounds(bounds.toMLNCoordinateBounds())
  }

  public actual fun invalidateTile(zoomLevel: Int, x: Int, y: Int) {
    impl.invalidateTileAtX(x = x.toULong(), y = y.toULong(), zoomLevel = zoomLevel.toULong())
  }

  public actual fun setData(zoomLevel: Int, x: Int, y: Int, data: FeatureCollection) {
    impl.setFeatures(
      features = data.map { it.toMLNShape() },
      inTileAtX = x.toULong(),
      y = y.toULong(),
      zoomLevel = zoomLevel.toULong(),
    )
  }

  private companion object {
    private fun buildOptionMap(options: ComputedSourceOptions) =
      buildMap<Any?, Any?> {
        put(MLNShapeSourceOptionMinimumZoomLevel, NSNumber(options.minZoom))
        put(MLNShapeSourceOptionMaximumZoomLevel, NSNumber(options.maxZoom))
        put(MLNShapeSourceOptionBuffer, NSNumber(options.buffer))
        put(MLNShapeSourceOptionSimplificationTolerance, NSNumber(options.tolerance.toDouble()))
        put(MLNShapeSourceOptionWrapsCoordinates, NSNumber(options.wrap))
        put(MLNShapeSourceOptionClipsCoordinates, NSNumber(options.clip))
      }
  }
}
