package org.maplibre.compose.sources

import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.install
import org.maplibre.compose.testing.MapTestResult
import org.maplibre.compose.testing.createMapFixture
import org.maplibre.compose.testing.runMapTest
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.Geometry
import org.maplibre.spatialk.geojson.Point
import org.maplibre.spatialk.geojson.Position
import org.maplibre.spatialk.geojson.dsl.addFeature
import org.maplibre.spatialk.geojson.dsl.buildFeatureCollection

/** The rendered query preserves the engine-specific numeric type of the cluster identifier. */
class GeoJsonClusterTest {
  @Test
  fun a_geojson_handle_answers_cluster_queries(): MapTestResult = runMapTest {
    createMapFixture().use { fixture ->
      fixture.loadStyle(BaseStyle.Empty)
      fixture.state.setCameraPosition(CameraPosition(target = Position(0.0, 0.0), zoom = ZOOM))
      val binding = checkNotNull(fixture.style)
      val source =
        GeoJsonSource(
          id = "points",
          data = GeoJsonData.Features(nearbyPoints()),
          options = GeoJsonOptions(cluster = true, clusterRadius = 200, clusterMaxZoom = 14),
        )
      binding.install(source)
      binding.install(CircleLayer("clusters", source))
      val handle = assertIs<GeoJsonSourceHandle>(fixture.state.style.source("points"))

      suspend fun rendered(): List<Feature<Geometry, JsonObject?>> =
        fixture.state.queryRenderedFeatures(
          DpRect(0.dp, 0.dp, 512.dp, 512.dp),
          layerIds = null,
        )

      fixture.awaitMapReady()
      fixture.pumpUntil("a cluster to render") { rendered().any(handle::isCluster) }
      val cluster = rendered().first(handle::isCluster)

      assertTrue(handle.getClusterExpansionZoom(cluster) > ZOOM)
      assertTrue(handle.getClusterChildren(cluster).features.isNotEmpty())
      assertEquals(2, handle.getClusterLeaves(cluster, limit = 2, offset = 0).features.size)
      assertEquals(POINT_COUNT - 1, handle.getClusterLeaves(cluster, 10, 1).features.size)
    }
  }

  private fun nearbyPoints(): FeatureCollection<Geometry, JsonObject?> = buildFeatureCollection {
    repeat(POINT_COUNT) { index ->
      addFeature(geometry = Point(Position(index * 0.001, 0.0)))
    }
  }

  private companion object {
    const val POINT_COUNT = 3
    const val ZOOM = 4.0
  }
}
