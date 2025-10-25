package org.maplibre.compose.offline

import kotlin.experimental.ExperimentalNativeApi
import kotlin.test.Test
import kotlinx.cinterop.ExperimentalForeignApi
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.Polygon
import org.maplibre.spatialk.geojson.Position

@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)
class TilePackDefinitionTest {
  @Test
  fun convertTilePyramid() {
    val noMax =
      OfflinePackDefinition.TilePyramid(
        styleUrl = "https://example.com",
        bounds =
          BoundingBox(
            southwest = Position(longitude = -10.0, latitude = -11.0),
            northeast = Position(longitude = 12.0, latitude = 13.0),
          ),
        minZoom = 3,
        maxZoom = null, // infinity
      )
    assert(noMax.toMLNOfflineRegion().toOfflinePackDefinition() == noMax)

    val minMax =
      OfflinePackDefinition.TilePyramid(
        styleUrl = "https://example.com",
        bounds =
          BoundingBox(
            southwest = Position(longitude = -10.0, latitude = -11.0),
            northeast = Position(longitude = 12.0, latitude = 13.0),
          ),
        minZoom = 3,
        maxZoom = 10,
      )
    assert(minMax.toMLNOfflineRegion().toOfflinePackDefinition() == minMax)
  }

  @Test
  fun convertShape() {
    val noMax =
      OfflinePackDefinition.Shape(
        styleUrl = "https://example.com",
        shape =
          Polygon(
            listOf(
              Position(longitude = -10.0, latitude = -11.0), // southwest
              Position(longitude = -10.0, latitude = 13.0), // northwest
              Position(longitude = 12.0, latitude = 13.0), // northeast
              Position(longitude = 12.0, latitude = -11.0), // southeast
              Position(longitude = -10.0, latitude = -11.0), // southwest
            )
          ),
        minZoom = 3,
        maxZoom = null, // infinity
      )
    assert(noMax.toMLNOfflineRegion().toOfflinePackDefinition() == noMax)

    val minMax =
      OfflinePackDefinition.Shape(
        styleUrl = "https://example.com",
        shape =
          Polygon(
            listOf(
              Position(longitude = -10.0, latitude = -11.0),
              Position(longitude = -10.0, latitude = 13.0),
              Position(longitude = 12.0, latitude = 13.0),
              Position(longitude = 12.0, latitude = -11.0),
              Position(longitude = -10.0, latitude = -11.0),
            )
          ),
        minZoom = 3,
        maxZoom = 10,
      )
    assert(minMax.toMLNOfflineRegion().toOfflinePackDefinition() == minMax)
  }
}
