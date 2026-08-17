package org.maplibre.compose.sources

import kotlinx.cinterop.CValue
import org.maplibre.compose.util.toBoundingBox
import org.maplibre.compose.util.toMLNCoordinateBounds
import org.maplibre.compose.util.toMLNShape
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.FeatureCollection
import platform.Foundation.NSNumber
import platform.darwin.NSObject
import platform.darwin.NSUInteger
import swiftPMImport.org.maplibre.compose.lib.maplibre.compose.MLNComputedShapeSource
import swiftPMImport.org.maplibre.compose.lib.maplibre.compose.MLNComputedShapeSourceDataSourceProtocol
import swiftPMImport.org.maplibre.compose.lib.maplibre.compose.MLNCoordinateBounds
import swiftPMImport.org.maplibre.compose.lib.maplibre.compose.MLNShape
import swiftPMImport.org.maplibre.compose.lib.maplibre.compose.MLNShapeSourceOptionBuffer
import swiftPMImport.org.maplibre.compose.lib.maplibre.compose.MLNShapeSourceOptionClipsCoordinates
import swiftPMImport.org.maplibre.compose.lib.maplibre.compose.MLNShapeSourceOptionMaximumZoomLevel
import swiftPMImport.org.maplibre.compose.lib.maplibre.compose.MLNShapeSourceOptionMinimumZoomLevel
import swiftPMImport.org.maplibre.compose.lib.maplibre.compose.MLNShapeSourceOptionSimplificationTolerance
import swiftPMImport.org.maplibre.compose.lib.maplibre.compose.MLNShapeSourceOptionWrapsCoordinates

public actual class ComputedSource : Source {
  override val impl: MLNComputedShapeSource

  internal constructor(impl: MLNComputedShapeSource) {
    this.impl = impl
  }

  public actual constructor(
    id: String,
    options: ComputedSourceOptions,
    getFeatures: (bounds: BoundingBox, zoomLevel: Int) -> FeatureCollection<*, *>,
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

  public actual fun setData(zoomLevel: Int, x: Int, y: Int, data: FeatureCollection<*, *>) {
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
