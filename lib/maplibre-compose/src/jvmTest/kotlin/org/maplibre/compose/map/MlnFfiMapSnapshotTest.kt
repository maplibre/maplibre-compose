package org.maplibre.compose.map

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.mlnffi.FfiTestCache
import org.maplibre.compose.mlnffi.MlnFfiApplication
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.style.BaseStyle
import org.maplibre.spatialk.geojson.Point
import org.maplibre.spatialk.geojson.Position

/** [MapState.captureStillImage] renders a bare state with no [MaplibreMap] anywhere. */
class MlnFfiMapSnapshotTest {

  private val cache = FfiTestCache()

  private var state: MapState? = null

  @AfterTest
  fun cleanUp() {
    state?.close()
    MlnFfiApplication.resetForTest()
    cache.close()
  }

  private fun bareState(): MapState {
    cache.configure()
    return MapState().also { state = it }
  }

  @Test
  fun a_detached_state_renders_a_still_image() {
    val state = bareState()
    state.baseStyle = RED_BACKGROUND_STYLE
    state.setStyleContent {
      val dot = rememberGeoJsonSource(GeoJsonData.Features(Point(Position(0.0, 0.0))))
      CircleLayer(id = "dot", source = dot, color = const(Color.Blue), radius = const(30.dp))
    }

    val image = runBlocking {
      state.captureStillImage(width = 200.dp, height = 150.dp, timeout = 60.seconds)
    }

    assertEquals(200, image.width)
    assertEquals(150, image.height)
    val pixels = image.toPixelMap()
    assertColor(Color.Red, pixels[4, 4], "the base style's background at the corner")
    assertColor(Color.Blue, pixels[100, 75], "the content's circle at the camera target")
  }

  /** The snapshot publishes its viewport to the state, so viewport-conditioned content renders. */
  @Test
  fun a_snapshot_composes_content_conditioned_on_the_viewport() {
    val state = bareState()
    state.baseStyle = RED_BACKGROUND_STYLE
    state.setStyleContent {
      if (LocalMapState.current.viewport != null) {
        val dot = rememberGeoJsonSource(GeoJsonData.Features(Point(Position(0.0, 0.0))))
        CircleLayer(id = "dot", source = dot, color = const(Color.Blue), radius = const(30.dp))
      }
    }

    val image = runBlocking {
      state.captureStillImage(width = 200.dp, height = 150.dp, timeout = 60.seconds)
    }

    val pixels = image.toPixelMap()
    assertColor(Color.Blue, pixels[100, 75], "the viewport-conditioned circle at the target")
    assertNull(state.viewport, "the snapshot's viewport must not outlive it")
  }

  /** A premultiplied readback must divide out before packing, or translucent pixels darken. */
  @Test
  fun a_translucent_style_reads_back_straight_alpha() {
    val state = bareState()
    state.baseStyle = TRANSLUCENT_RED_STYLE

    val image = runBlocking {
      state.captureStillImage(width = 20.dp, height = 20.dp, timeout = 60.seconds)
    }

    val pixels = image.toPixelMap()
    assertColor(Color.Red.copy(alpha = 0.5f), pixels[10, 10], "the half-opacity background")
  }

  @Test
  fun a_snapshot_on_a_closed_state_throws() {
    val state = bareState()
    state.close()
    assertFailsWith<IllegalStateException> {
      runBlocking { state.captureStillImage(width = 10.dp, height = 10.dp) }
    }
  }

  @Test
  fun a_snapshot_with_an_attached_session_throws() {
    val state = bareState()
    state.attachSession(FakeMapAdapter())
    assertFailsWith<IllegalStateException> {
      runBlocking { state.captureStillImage(width = 10.dp, height = 10.dp) }
    }
  }

  private fun assertColor(expected: Color, actual: Color, description: String) {
    val close =
      abs(expected.red - actual.red) < TOLERANCE &&
        abs(expected.green - actual.green) < TOLERANCE &&
        abs(expected.blue - actual.blue) < TOLERANCE &&
        abs(expected.alpha - actual.alpha) < TOLERANCE
    assertTrue(close, "Expected $expected for $description, got $actual")
  }

  private companion object {
    const val TOLERANCE = 0.05f

    val RED_BACKGROUND_STYLE =
      BaseStyle.Json(
        """
        {"version":8,"sources":{},
         "layers":[{"id":"bg","type":"background","paint":{"background-color":"#ff0000"}}]}
        """
          .trimIndent()
      )

    val TRANSLUCENT_RED_STYLE =
      BaseStyle.Json(
        """
        {"version":8,"sources":{},
         "layers":[{"id":"bg","type":"background",
                    "paint":{"background-color":"#ff0000","background-opacity":0.5}}]}
        """
          .trimIndent()
      )
  }
}
