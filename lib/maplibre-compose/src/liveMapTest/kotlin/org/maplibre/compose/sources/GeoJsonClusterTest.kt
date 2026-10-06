package org.maplibre.compose.sources

import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.layers.TestLayer
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.install
import org.maplibre.compose.testing.MapLibreFlavor
import org.maplibre.compose.testing.MapTestResult
import org.maplibre.compose.testing.createMapFixture
import org.maplibre.compose.testing.mapLibreFlavor
import org.maplibre.compose.testing.runMapTest
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.Geometry
import org.maplibre.spatialk.geojson.Point
import org.maplibre.spatialk.geojson.Position
import org.maplibre.spatialk.geojson.dsl.addFeature
import org.maplibre.spatialk.geojson.dsl.buildFeatureCollection

class GeoJsonClusterTest {
  @Test
  fun cluster_queries_resolve_features_and_report_missing_clusters(): MapTestResult = runMapTest {
    createMapFixture().use { fixture ->
      fixture.loadStyle(BaseStyle.Empty)
      fixture.state.setCameraPosition(CameraPosition(target = Position(0.0, 0.0), zoom = Zoom))
      val binding = checkNotNull(fixture.style)
      val source =
        GeoJsonSource(
          id = "points",
          data = GeoJsonData.Features(nearbyPoints()),
          options = GeoJsonOptions(cluster = true, clusterRadius = 200, clusterMaxZoom = 14),
        )
      fixture.state.style.sources.add(source)
      binding.install(TestLayer("clusters", "circle", source))
      val handle = assertIs<GeoJsonSourceHandle>(fixture.state.style.sources["points"])

      suspend fun rendered(): List<Feature<Geometry, JsonObject?>> =
        fixture.state.queryRenderedFeatures(
          DpRect(0.dp, 0.dp, 512.dp, 512.dp),
          layerIds = null,
        )

      fixture.awaitMapReady()
      fixture.pumpUntil("a cluster to render") { rendered().any(handle::isCluster) }
      val cluster = rendered().first(handle::isCluster)

      assertTrue(assertNotNull(handle.getClusterExpansionZoom(cluster)) > Zoom)
      assertTrue(handle.getClusterChildren(cluster).features.isNotEmpty())
      assertEquals(2, handle.getClusterLeaves(cluster, limit = 2, offset = 0).features.size)
      assertEquals(PointCount - 1, handle.getClusterLeaves(cluster, 10, 1).features.size)

      handle.asMutable!!.setData(
        GeoJsonData.Features(
          buildFeatureCollection<Geometry, JsonObject?> { addFeature(Point(Position(0.0, 0.0))) }
        )
      )
      fixture.pumpUntil("the single point to replace the cluster") {
        val features = rendered()
        features.isNotEmpty() && features.none(handle::isCluster)
      }
      // GL JS computes expansion zoom from the ID without validating it.
      if (mapLibreFlavor == MapLibreFlavor.Native) {
        assertNull(handle.getClusterExpansionZoom(cluster))
      }
      assertEquals(emptyList(), handle.getClusterChildren(cluster).features)
      assertEquals(emptyList(), handle.getClusterLeaves(cluster, 10, 0).features)
    }
  }

  private fun nearbyPoints(): FeatureCollection<Geometry, JsonObject?> = buildFeatureCollection {
    repeat(PointCount) { index ->
      addFeature(geometry = Point(Position(index * 0.001, 0.0)))
    }
  }

  private companion object {
    const val PointCount = 3
    const val Zoom = 4.0
  }
}
