package dev.sargunv.maplibrecompose.compose

import dev.sargunv.maplibrecompose.compose.offline.OfflineRegionDefinition
import dev.sargunv.maplibrecompose.compose.offline.toMLNOfflineRegion
import dev.sargunv.maplibrecompose.compose.offline.toRegionDefinition
import io.github.dellisd.spatialk.geojson.BoundingBox
import io.github.dellisd.spatialk.geojson.Polygon
import io.github.dellisd.spatialk.geojson.Position
import kotlin.experimental.ExperimentalNativeApi
import kotlin.test.Test
import kotlinx.cinterop.ExperimentalForeignApi

@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)
class OfflineRegionDefinitionTest {
  @Test
  fun convertTilePyramid() {
    val noMax =
      OfflineRegionDefinition.TilePyramid(
        styleUrl = "https://example.com",
        bounds =
          BoundingBox(
            southwest = Position(longitude = -10.0, latitude = -11.0),
            northeast = Position(longitude = 12.0, latitude = 13.0),
          ),
        minZoom = 3,
        maxZoom = null, // infinity
      )
    assert(noMax.toMLNOfflineRegion().toRegionDefinition() == noMax)

    val minMax =
      OfflineRegionDefinition.TilePyramid(
        styleUrl = "https://example.com",
        bounds =
          BoundingBox(
            southwest = Position(longitude = -10.0, latitude = -11.0),
            northeast = Position(longitude = 12.0, latitude = 13.0),
          ),
        minZoom = 3,
        maxZoom = 10,
      )
    assert(minMax.toMLNOfflineRegion().toRegionDefinition() == minMax)
  }

  @Test
  fun convertShape() {
    val noMax =
      OfflineRegionDefinition.Shape(
        styleUrl = "https://example.com",
        geometry =
          Polygon(
            listOf(
              Position(longitude = -10.0, latitude = -11.0), // southwest
              Position(longitude = -10.0, latitude = 13.0), // northwest
              Position(longitude = 12.0, latitude = 13.0), // northeast
              Position(longitude = 12.0, latitude = -11.0), // southeast
            )
          ),
        minZoom = 3,
        maxZoom = null, // infinity
      )
    assert(noMax.toMLNOfflineRegion().toRegionDefinition() == noMax)

    val minMax =
      OfflineRegionDefinition.Shape(
        styleUrl = "https://example.com",
        geometry =
          Polygon(
            listOf(
              Position(longitude = -10.0, latitude = -11.0),
              Position(longitude = -10.0, latitude = 13.0),
              Position(longitude = 12.0, latitude = 13.0),
              Position(longitude = 12.0, latitude = -11.0),
            )
          ),
        minZoom = 3,
        maxZoom = 10,
      )
    assert(minMax.toMLNOfflineRegion().toRegionDefinition() == minMax)
  }
}
