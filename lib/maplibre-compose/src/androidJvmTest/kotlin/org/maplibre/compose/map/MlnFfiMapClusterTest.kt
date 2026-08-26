@file:OptIn(ExperimentalAtomicApi::class)

package org.maplibre.compose.map

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import co.touchlab.kermit.Logger
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.mlnffi.FfiTestPlatform
import org.maplibre.compose.mlnffi.MlnFfiRuntimeOptions
import org.maplibre.compose.mlnffi.runFfiComposeUiTest
import org.maplibre.compose.mlnffi.setFfiTestMapContent
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.GeoJsonOptions
import org.maplibre.compose.sources.GeoJsonSource
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.style.BaseStyle
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.Geometry
import org.maplibre.spatialk.geojson.Point
import org.maplibre.spatialk.geojson.Position
import org.maplibre.spatialk.geojson.dsl.addFeature
import org.maplibre.spatialk.geojson.dsl.buildFeatureCollection

/**
 * Exercises the supercluster queries against a real clustered source. They answer only from a
 * render pass, and the cluster feature must come from a rendered query rather than being
 * hand-built: MapLibre matches the cluster id with an exact unsigned-integer type check, and a
 * mistyped id misses silently with a success status.
 */
@OptIn(ExperimentalTestApi::class)
class MlnFfiMapClusterTest {

  private val cacheFile = FfiTestPlatform.createCacheFile()

  private val runtimeOptions =
    MlnFfiRuntimeOptions(cacheFile = cacheFile, maximumCacheSizeBytes = null)

  @AfterTest
  fun cleanUp() {
    FfiTestPlatform.deleteCacheFile(cacheFile)
  }

  @Test
  fun a_clustered_source_answers_the_supercluster_queries() = runFfiComposeUiTest {
    val frames = AtomicInt(0)

    lateinit var source: GeoJsonSource
    lateinit var state: MapState

    setFfiTestMapContent(runtimeOptions) {
      val mapState =
        rememberMapState(
          cameraPosition = CameraPosition(target = Position(0.0, 0.0), zoom = START_ZOOM),
          baseStyle = BaseStyle.Empty,
        ) {
          source =
            rememberGeoJsonSource(
              data = GeoJsonData.Features(nearbyPoints()),
              // Zoomed out far enough that all three points fall inside one cluster.
              options = GeoJsonOptions(cluster = true, clusterRadius = 200, clusterMaxZoom = 14),
            )
          CircleLayer(id = "clusters", source = source, color = const(Color.Red))
        }
      state = mapState
      MaplibreMap(
        state = mapState,
        modifier = Modifier.fillMaxSize(),
        logger = Logger.withTag("cluster-test"),
        onFrame = { frames.incrementAndFetch() },
      )
    }

    waitUntil(timeoutMillis = SETTLE_TIMEOUT_MILLIS) { frames.load() > 0 }
    val session = assertNotNull(state.attachedAdapter as? MlnFfiMapCore, "no FFI session")

    fun queryAll(): List<Feature<Geometry, JsonObject?>> {
      val size = onRoot().fetchSemanticsNode().size
      return runBlocking {
        session.queryRenderedFeatures(
          rect = DpRect(left = 0.dp, top = 0.dp, right = size.width.dp, bottom = size.height.dp),
          layerIds = null,
          predicate = null,
        )
      }
    }

    // Clustering happens during tile building, so a cluster only exists once the map has rendered
    // one, on a thread of its own rather than after any fixed number of Compose frames.
    waitUntil(timeoutMillis = SETTLE_TIMEOUT_MILLIS) { queryAll().any { source.isCluster(it) } }

    val hits = queryAll()
    val cluster =
      assertNotNull(
        hits.firstOrNull { source.isCluster(it) },
        "No cluster was rendered after ${frames.load()} frames; ${hits.size} hits were $hits",
      )

    val expansionZoom = source.getClusterExpansionZoom(cluster)
    assertTrue(
      expansionZoom > START_ZOOM,
      "Expected an expansion zoom past $START_ZOOM, got $expansionZoom",
    )

    assertTrue(
      source.getClusterChildren(cluster).features.isNotEmpty(),
      "Expected the cluster to report children",
    )

    // MapLibre reads the limit with the same exact unsigned check as the cluster id, and silently
    // substitutes its own default of ten when the type is wrong.
    assertEquals(
      2,
      source.getClusterLeaves(cluster, limit = 2, offset = 0).features.size,
      "A limit of 2 should return 2 leaves, not MapLibre's default of 10",
    )
    assertEquals(
      POINT_COUNT,
      source.getClusterLeaves(cluster, limit = 10, offset = 0).features.size,
      "A limit above the cluster size should return every leaf",
    )
  }

  private fun nearbyPoints(): FeatureCollection<Geometry, JsonObject?> = buildFeatureCollection {
    repeat(POINT_COUNT) { index ->
      addFeature(geometry = Point(Position(longitude = index * 0.001, latitude = 0.0)))
    }
  }

  private companion object {
    const val POINT_COUNT = 3

    /** Well below clusterMaxZoom, so the points are still clustered. */
    const val START_ZOOM = 4.0

    const val SETTLE_TIMEOUT_MILLIS = 30_000L
  }
}
