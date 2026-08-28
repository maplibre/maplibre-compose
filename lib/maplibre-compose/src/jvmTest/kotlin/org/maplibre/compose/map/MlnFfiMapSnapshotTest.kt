package org.maplibre.compose.map

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.Density
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

  /**
   * One bare state walks the snapshot lifecycle: a broken style fails the capture, a replacement
   * style recovers and renders viewport-conditioned content, a style switch renders again, and an
   * attached session and then a close each refuse further captures.
   */
  @Test
  fun a_bare_state_fails_recovers_captures_twice_and_refuses_after_attach_and_close() {
    val state = bareState()

    // Step 1: a broken base style fails the capture instead of hanging.
    state.baseStyle = BaseStyle.Json("this is not a style")
    assertFailsWith<IllegalStateException>("the broken style must fail the snapshot") {
      runBlocking { state.captureStillImage(width = 10.dp, height = 10.dp, timeout = 30.seconds) }
    }

    // Step 2: a new base style clears the load failure, and the capture renders content that
    // composes only once the snapshot has published its viewport to the state.
    state.baseStyle = RED_BACKGROUND_STYLE
    state.setStyleComposition {
      if (LocalMapState.current.viewport != null) {
        val dot = rememberGeoJsonSource(GeoJsonData.Features(Point(Position(0.0, 0.0))))
        CircleLayer(id = "dot", source = dot, color = const(Color.Blue), radius = const(30.dp))
      }
    }
    val first = runBlocking {
      state.captureStillImage(width = 200.dp, height = 150.dp, timeout = 60.seconds)
    }
    assertEquals(200, first.width, "the capture must be the requested width")
    assertEquals(150, first.height, "the capture must be the requested height")
    val firstPixels = first.toPixelMap()
    assertColor(Color.Red, firstPixels[4, 4], "the base style's background at the corner")
    assertColor(Color.Blue, firstPixels[100, 75], "the viewport-conditioned circle at the target")
    assertNull(state.viewport, "the snapshot's viewport must not outlive it")

    // Step 3: the same state captures again after a base-style switch, and a premultiplied
    // readback divides out before packing, so translucent pixels come back straight-alpha.
    state.clearStyleComposition()
    state.baseStyle = TRANSLUCENT_RED_STYLE
    val second = runBlocking {
      state.captureStillImage(width = 20.dp, height = 20.dp, timeout = 60.seconds)
    }
    assertColor(
      Color.Red.copy(alpha = 0.5f),
      second.toPixelMap()[10, 10],
      "the half-opacity background",
    )

    // Step 4: a capture with an attached session throws.
    state.attachSession(FakeMapAdapter())
    assertFailsWith<IllegalStateException>("a snapshot with an attached session must throw") {
      runBlocking { state.captureStillImage(width = 10.dp, height = 10.dp) }
    }
    state.detachSession()

    // Step 5: a capture on a closed state throws.
    state.close()
    assertFailsWith<IllegalStateException>("a snapshot on a closed state must throw") {
      runBlocking { state.captureStillImage(width = 10.dp, height = 10.dp) }
    }
  }

  @Test
  fun the_constructor_density_scales_the_still_image_pixels() {
    cache.configure()
    val dense = MapState(density = Density(2f))
    state = dense
    dense.baseStyle = RED_BACKGROUND_STYLE

    val image = runBlocking {
      dense.captureStillImage(width = 20.dp, height = 30.dp, timeout = 60.seconds)
    }

    assertEquals(40, image.width, "a density of 2 doubles the pixel width of the dp size")
    assertEquals(60, image.height, "a density of 2 doubles the pixel height of the dp size")
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
