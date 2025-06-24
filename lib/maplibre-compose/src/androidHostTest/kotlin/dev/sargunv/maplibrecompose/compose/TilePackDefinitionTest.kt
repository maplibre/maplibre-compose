package dev.sargunv.maplibrecompose.compose

import dev.sargunv.maplibrecompose.compose.offline.OfflinePackDefinition
import dev.sargunv.maplibrecompose.compose.offline.toMLNOfflineRegionDefinition
import dev.sargunv.maplibrecompose.compose.offline.toOfflinePackDefinition
import io.github.dellisd.spatialk.geojson.BoundingBox
import io.github.dellisd.spatialk.geojson.Polygon
import io.github.dellisd.spatialk.geojson.Position
import kotlin.test.Test

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
    assert(noMax.toMLNOfflineRegionDefinition(1f).toOfflinePackDefinition() == noMax)

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
    assert(minMax.toMLNOfflineRegionDefinition(1f).toOfflinePackDefinition() == minMax)
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
            )
          ),
        minZoom = 3,
        maxZoom = null, // infinity
      )
    assert(noMax.toMLNOfflineRegionDefinition(1f).toOfflinePackDefinition() == noMax)

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
            )
          ),
        minZoom = 3,
        maxZoom = 10,
      )
    assert(minMax.toMLNOfflineRegionDefinition(1f).toOfflinePackDefinition() == minMax)
  }
}
