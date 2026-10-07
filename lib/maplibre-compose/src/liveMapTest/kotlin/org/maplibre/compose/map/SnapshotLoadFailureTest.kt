package org.maplibre.compose.map

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import kotlin.concurrent.Volatile
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.resource.MapResourceError
import org.maplibre.compose.resource.MapResourceLoad
import org.maplibre.compose.resource.MapResourceProvider
import org.maplibre.compose.sources.CustomGeometrySource
import org.maplibre.compose.sources.CustomGeometrySourceOptions
import org.maplibre.compose.sources.CustomVectorTileSource
import org.maplibre.compose.sources.CustomVectorTileSourceOptions
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.testing.MapTestResult
import org.maplibre.compose.testing.runMapTest
import org.maplibre.compose.testing.withTestMapRuntime

class SnapshotLoadFailureTest {

  @Test
  fun a_failing_vector_tile_provider_fails_each_capture_until_it_recovers(): MapTestResult =
    runMapTest {
      val failure = IllegalStateException("fixture provider failure")
      val state = ProviderState()
      val source =
        CustomVectorTileSource("custom", CustomVectorTileSourceOptions(minZoom = 0, maxZoom = 0)) {
          if (state.failing) throw failure else byteArrayOf()
        }
      withTestMapRuntime { runtime ->
        val snapshotter =
          runtime.createSnapshotter(EmptyStyle) {
            CircleLayer("points", source, sourceLayer = "points")
          }
        try {
          // A failed tile must not leave the next capture waiting or drawn without it.
          repeat(2) {
            val error = assertFailsWith<MapSnapshotException> { snapshotter.capture(Request) }
            assertTrue(error.causes().any { it === failure }, "the cause is the provider's")
          }

          state.failing = false
          snapshotter.capture(Request)
        } finally {
          snapshotter.close()
          snapshotter.awaitClosed()
        }
      }
    }

  /**
   * A provider's own cancellation, such as its timeout, fails the capture rather than cancel it.
   */
  @Test
  fun a_geometry_tile_provider_timeout_fails_the_capture(): MapTestResult = runMapTest {
    val failure = CancellationException("fixture provider timeout")
    val source =
      CustomGeometrySource("custom", CustomGeometrySourceOptions(minZoom = 0, maxZoom = 0)) {
        throw failure
      }
    withTestMapRuntime { runtime ->
      val snapshotter = runtime.createSnapshotter(EmptyStyle) { CircleLayer("points", source) }
      try {
        val error = assertFailsWith<MapSnapshotException> { snapshotter.capture(Request) }
        assertTrue(error.causes().any { it === failure }, "the cause is the provider's")
      } finally {
        snapshotter.close()
        snapshotter.awaitClosed()
      }
    }
  }

  @Test
  fun a_failed_tile_request_fails_the_capture_and_a_missing_tile_does_not(): MapTestResult =
    runMapTest {
      val resources =
        MapResourceProvider(
          accepts = { it.url.startsWith(TileHost) },
          load = { request ->
            if ("missing" in request.url) MapResourceLoad.Failed(MapResourceError.NotFound, "none")
            else MapResourceLoad.Failed(MapResourceError.Server, "fixture server failure")
          },
        )
      withTestMapRuntime(resources) { runtime ->
        val failing = runtime.createSnapshotter(tileStyle("$TileHost/failing/{z}/{x}/{y}.pbf"))
        val missing = runtime.createSnapshotter(tileStyle("$TileHost/missing/{z}/{x}/{y}.pbf"))
        try {
          val error = assertFailsWith<MapSnapshotException> { failing.capture(Request) }
          assertTrue("fixture server failure" in error.message.orEmpty(), error.message)

          missing.capture(Request)
        } finally {
          failing.close()
          missing.close()
          failing.awaitClosed()
          missing.awaitClosed()
        }
      }
    }

  private class ProviderState {
    @Volatile var failing = true
  }

  private companion object {
    const val TileHost = "https://tiles.example.test"
    val Request = MapSnapshotRequest(DpSize(64.dp, 64.dp), cameraPosition = CameraPosition())
    val EmptyStyle = BaseStyle.Json("""{"version":8,"sources":{},"layers":[]}""")

    fun tileStyle(tiles: String) =
      BaseStyle.Json(
        """
        {
          "version": 8,
          "sources": {"tiles": {"type": "vector", "tiles": ["$tiles"], "maxzoom": 0}},
          "layers": [{"id": "points", "type": "circle", "source": "tiles", "source-layer": "points"}]
        }
        """
          .trimIndent()
      )

    fun Throwable.causes(): Sequence<Throwable> = generateSequence(this) { it.cause }
  }
}
