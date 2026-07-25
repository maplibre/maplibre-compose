package org.maplibre.compose.map

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import co.touchlab.kermit.Logger
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.CameraState
import org.maplibre.compose.camera.rememberCameraState
import org.maplibre.compose.desktop.DesktopRuntimeOptions
import org.maplibre.compose.desktop.HeadlessVulkanMapHostFactory
import org.maplibre.compose.desktop.LocalDesktopMapHostFactory
import org.maplibre.compose.desktop.LocalDesktopRuntimeOptions
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.GeoJsonOptions
import org.maplibre.compose.sources.GeoJsonSource
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.style.BaseStyle
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.Geometry
import org.maplibre.spatialk.geojson.Point
import org.maplibre.spatialk.geojson.Position
import org.maplibre.spatialk.geojson.dsl.addFeature
import org.maplibre.spatialk.geojson.dsl.buildFeatureCollection

/**
 * Exercises the supercluster queries against a real clustered source.
 *
 * These only answer from a render pass, so they need a rendered frame — and the cluster feature has
 * to come from an actual rendered query rather than being hand-built, because the round trip is
 * where the bug lives. MapLibre looks the cluster id up with an exact unsigned-integer type check,
 * so an id that comes back encoded as a signed integer does not fail: the lookup misses and the
 * query returns an empty result with a success status. Nothing but an end-to-end assertion catches
 * that.
 */
@OptIn(ExperimentalTestApi::class)
class DesktopMapClusterTest {

  private val cacheDirectory = Files.createTempDirectory("maplibre-cluster-test")

  private val runtimeOptions =
    DesktopRuntimeOptions(
      cachePath = cacheDirectory.resolve("cache.db"),
      maximumCacheSizeBytes = null,
    )

  @AfterTest
  fun cleanUp() {
    cacheDirectory.toFile().deleteRecursively()
  }

  @Test
  fun `a clustered source answers the supercluster queries`() = runComposeUiTest {
    val factory = HeadlessVulkanMapHostFactory.createOrNull()
    if (factory == null) {
      System.err.println("Skipping: no usable Vulkan implementation")
      return@runComposeUiTest
    }

    lateinit var source: GeoJsonSource
    lateinit var cameraState: CameraState

    setContent {
      CompositionLocalProvider(
        LocalDesktopMapHostFactory provides factory,
        LocalDesktopRuntimeOptions provides runtimeOptions,
      ) {
        cameraState =
          rememberCameraState(
            firstPosition = CameraPosition(target = Position(0.0, 0.0), zoom = START_ZOOM)
          )
        MaplibreMap(
          modifier = Modifier.fillMaxSize(),
          baseStyle = BaseStyle.Empty,
          cameraState = cameraState,
          logger = Logger.withTag("cluster-test"),
        ) {
          source =
            rememberGeoJsonSource(
              data = GeoJsonData.Features(nearbyPoints()),
              // Zoomed out far enough that all three points fall inside one cluster.
              options = GeoJsonOptions(cluster = true, clusterRadius = 200, clusterMaxZoom = 14),
            )
          CircleLayer(id = "clusters", source = source, color = const(Color.Red))
        }
      }
    }

    // Clustering happens during tile building, so the frames are what produce a cluster to query.
    repeat(SETTLE_ROUNDS) { waitForIdle() }

    val session = assertNotNull(cameraState.map as? DesktopMapSession, "no desktop session")
    // The real surface, not a guess: a box larger than the viewport comes back empty, so
    // over-covering is not a safe way to avoid depending on the size.
    val host = factory.created.single()
    val extent = host.currentExtent
    val hits =
      session.queryRenderedFeatures(
        rect = DpRect(left = 0.dp, top = 0.dp, right = extent.width.dp, bottom = extent.height.dp),
        layerIds = null,
        predicate = null,
      )
    val cluster =
      assertNotNull(
        hits.firstOrNull { source.isCluster(it) },
        "No cluster was rendered in ${extent.width}x${extent.height} after " +
          "${host.renderedFrames} rendered frames; ${hits.size} hits were $hits",
      )

    // The reported bug: a cluster reported no expansion zoom, and the demo animated to it, which
    // showed the whole world.
    val expansionZoom = source.getClusterExpansionZoom(cluster)
    assertTrue(
      expansionZoom > START_ZOOM,
      "Expected an expansion zoom past $START_ZOOM, got $expansionZoom",
    )

    assertTrue(
      source.getClusterChildren(cluster).features.isNotEmpty(),
      "Expected the cluster to report children",
    )

    // The limit is the assertion that matters. MapLibre reads it with the same exact unsigned
    // check as the cluster id and silently substitutes its own default of ten when the type is
    // wrong — so a wrongly typed limit still returns every leaf and looks like it worked.
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

    const val SETTLE_ROUNDS = 30
  }
}
