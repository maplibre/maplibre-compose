package org.maplibre.compose.sources

import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.layers.CircleLayerDescriptor
import org.maplibre.compose.style.BaseStyle
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

/**
 * The cluster feature has to come from a rendered query rather than being hand-built: MapLibre
 * Native matches the cluster id with an exact unsigned-integer check, and a mistyped id misses
 * silently.
 */
class GeoJsonClusterTest {

  @Test
  fun a_clustered_source_answers_the_supercluster_queries(): MapTestResult = runMapTest {
    createMapFixture().use { fixture ->
      fixture.loadStyle(BaseStyle.Empty)
      fixture.session.setCameraPosition(CameraPosition(target = Position(0.0, 0.0), zoom = ZOOM))
      val style = assertNotNull(fixture.style)

      val source =
        GeoJsonSource(
          id = "points",
          data = GeoJsonData.Features(nearbyPoints()),
          // Zoomed out far enough that every point falls inside one cluster.
          options = GeoJsonOptions(cluster = true, clusterRadius = 200, clusterMaxZoom = 14),
        )
      style.addSource(source)
      style.addLayer(CircleLayerDescriptor("clusters", source))

      suspend fun queryAll(): List<Feature<Geometry, JsonObject?>> =
        fixture.session.queryRenderedFeatures(
          rect = DpRect(left = 0.dp, top = 0.dp, right = 512.dp, bottom = 512.dp),
          layerIds = null,
          predicate = null,
        )

      // A query reads the render session, which MapLibre Native only builds on the first frame.
      fixture.awaitMapReady()
      // Clustering happens while a tile is built, so no fixed number of frames will do.
      fixture.pumpUntil("a cluster to be rendered") { queryAll().any { source.isCluster(it) } }
      val cluster = queryAll().first { source.isCluster(it) }

      val expansionZoom = checkNotNull(style.clusterExpansionZoom(source.id, cluster))
      assertTrue(expansionZoom > ZOOM, "Expected an expansion zoom past $ZOOM, got $expansionZoom")

      assertTrue(
        checkNotNull(style.clusterChildren(source.id, cluster)).features.isNotEmpty(),
        "Expected the cluster to report children",
      )

      // MapLibre Native reads the limit with the same exact unsigned check as the cluster id, and
      // silently substitutes its own default of ten when the type is wrong.
      assertEquals(
        2,
        checkNotNull(style.clusterLeaves(source.id, cluster, limit = 2, offset = 0)).features.size,
        "A limit of 2 should return 2 leaves, not a default of 10",
      )
      assertEquals(
        POINT_COUNT,
        checkNotNull(style.clusterLeaves(source.id, cluster, limit = 10, offset = 0)).features.size,
        "A limit above the cluster size should return every leaf",
      )
      assertEquals(
        POINT_COUNT - 1,
        checkNotNull(style.clusterLeaves(source.id, cluster, limit = 10, offset = 1)).features.size,
        "An offset should skip that many leaves",
      )
    }
  }

  @Test
  fun a_cluster_query_on_a_plain_feature_answers_empty(): MapTestResult = runMapTest {
    createMapFixture().use { fixture ->
      fixture.loadStyle(BaseStyle.Empty)
      val style = assertNotNull(fixture.style)

      val source =
        GeoJsonSource(
          "points",
          GeoJsonData.Features(nearbyPoints()),
          GeoJsonOptions(cluster = true),
        )
      style.addSource(source)

      val plain = nearbyPoints().features.first()
      assertTrue(!source.isCluster(plain))
      assertEquals(0.0, source.getClusterExpansionZoom(plain))
      assertEquals(0, source.getClusterChildren(plain).features.size)
      assertEquals(0, source.getClusterLeaves(plain, limit = 10, offset = 0).features.size)
    }
  }

  private fun nearbyPoints(): FeatureCollection<Geometry, JsonObject?> = buildFeatureCollection {
    repeat(POINT_COUNT) { index ->
      addFeature(geometry = Point(Position(longitude = index * 0.001, latitude = 0.0)))
    }
  }

  private companion object {
    const val POINT_COUNT = 3

    /** Well below clusterMaxZoom, so the points are still clustered. */
    const val ZOOM = 4.0
  }
}
