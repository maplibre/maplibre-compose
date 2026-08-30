package org.maplibre.compose.layers

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.DpOffset
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.coroutines.runBlocking
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.map.MapState
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.rememberMapState
import org.maplibre.compose.mlnffi.FfiTestPlatform
import org.maplibre.compose.mlnffi.MlnFfiRuntimeOptions
import org.maplibre.compose.mlnffi.runFfiComposeUiTest
import org.maplibre.compose.mlnffi.setFfiTestMapContent
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.StyleComposition
import org.maplibre.compose.util.ClickResult
import org.maplibre.spatialk.geojson.Position

/**
 * Two overlapping fill layers cover the whole viewport, so a click at the centre hits both. Only
 * the order they sit in decides which handler runs first, and that order is the native style's, not
 * the composition's.
 *
 * An anchor is what lets the two disagree: a layer composed first can be drawn in front of one
 * composed later. Dispatching in composition order was #69, fixed by #93, and nothing but a
 * rendered map can catch it coming back — the dispatch reads the live style's layer list and asks
 * the renderer what each layer actually covers.
 */
@OptIn(ExperimentalTestApi::class)
class LayerClickOrderTest {

  private val cacheFile = FfiTestPlatform.createCacheFile()

  private val runtimeOptions =
    MlnFfiRuntimeOptions(cacheFile = cacheFile, maximumCacheSizeBytes = null)

  /** Which layers were offered the event, in the order the map offered them. */
  private val clicked = mutableListOf<String>()
  private val longClicked = mutableListOf<String>()

  @AfterTest
  fun cleanUp() {
    FfiTestPlatform.deleteCacheFile(cacheFile)
  }

  @Test
  fun a_click_goes_to_the_layer_in_front() =
    runLayerClickTest(composeFrontLayerFirst = false) { center ->
      onRoot().performMouseInput { click(center) }

      waitUntil(timeoutMillis = TIMEOUT) { clicked.isNotEmpty() }
      waitForIdle()
      assertEquals(listOf(FRONT), clicked)
    }

  /**
   * The same map, composed the other way round: the front layer is composed first and the back
   * layer second, with an anchor putting it behind. Dispatching in composition order would offer
   * the click to whichever layer was composed first or last, and one of the two arrangements would
   * catch it.
   */
  @Test
  fun a_click_goes_to_the_layer_in_front_even_when_it_was_composed_first() =
    runLayerClickTest(composeFrontLayerFirst = true) { center ->
      onRoot().performMouseInput { click(center) }

      waitUntil(timeoutMillis = TIMEOUT) { clicked.isNotEmpty() }
      waitForIdle()
      assertEquals(listOf(FRONT), clicked)
    }

  @Test
  fun a_click_the_front_layer_passes_falls_through_to_the_layer_behind() =
    runLayerClickTest(composeFrontLayerFirst = true, frontResult = ClickResult.Pass) { center ->
      onRoot().performMouseInput { click(center) }

      waitUntil(timeoutMillis = TIMEOUT) { clicked.size == 2 }
      waitForIdle()
      assertEquals(listOf(FRONT, BACK), clicked)
    }

  @Test
  fun a_long_click_goes_to_the_layer_in_front_too() =
    runLayerClickTest(composeFrontLayerFirst = true) { center ->
      val map = onRoot()
      map.performTouchInput { down(0, center) }
      mainClock.advanceTimeBy(1_000)
      waitUntil(timeoutMillis = TIMEOUT) { longClicked.isNotEmpty() }
      map.performTouchInput { up(0) }
      waitForIdle()

      assertEquals(listOf(FRONT), longClicked)
      assertEquals(emptyList<String>(), clicked, "the long click also reported a click")
    }

  @Test
  fun a_long_click_the_front_layer_passes_falls_through_to_the_layer_behind() =
    runLayerClickTest(composeFrontLayerFirst = true, frontResult = ClickResult.Pass) { center ->
      val map = onRoot()
      map.performTouchInput { down(0, center) }
      mainClock.advanceTimeBy(1_000)
      waitUntil(timeoutMillis = TIMEOUT) { longClicked.size == 2 }
      map.performTouchInput { up(0) }
      waitForIdle()

      assertEquals(listOf(FRONT, BACK), longClicked)
    }

  /**
   * Composes two fill layers of the same world-covering polygon, waits until both are rendered and
   * queryable, then runs [body] with the centre of the map.
   *
   * [composeFrontLayerFirst] only changes the composition; either way [FRONT] ends up in front of
   * [BACK] in the style. [frontResult] is what [FRONT]'s handlers return, so a test can either stop
   * the event there or let it fall through.
   */
  private fun runLayerClickTest(
    composeFrontLayerFirst: Boolean,
    frontResult: ClickResult = ClickResult.Consume,
    body: ComposeUiTest.(center: Offset) -> Unit,
  ) = runFfiComposeUiTest {
    lateinit var mapState: MapState

    setFfiTestMapContent(runtimeOptions) {
      mapState =
        rememberMapState(
          initialCameraPosition = CameraPosition(target = Position(0.0, 0.0), zoom = START_ZOOM),
          initialBaseStyle = BaseStyle.Empty,
        )
      MaplibreMap(
        state = mapState,
        modifier = Modifier.fillMaxSize(),
        styleComposition =
          StyleComposition {
            val source = rememberGeoJsonSource(data = GeoJsonData.JsonString(WORLD_POLYGON))

            val front: @Composable () -> Unit = {
              FillLayer(
                id = FRONT,
                source = source,
                color = const(Color.Red),
                onClick = {
                  clicked += FRONT
                  frontResult
                },
                onLongClick = {
                  longClicked += FRONT
                  frontResult
                },
              )
            }
            val back: @Composable () -> Unit = {
              FillLayer(
                id = BACK,
                source = source,
                color = const(Color.Blue),
                onClick = {
                  clicked += BACK
                  ClickResult.Consume
                },
                onLongClick = {
                  longClicked += BACK
                  ClickResult.Consume
                },
              )
            }

            if (composeFrontLayerFirst) {
              // `back` is composed second, and the anchor is the only reason it ends up behind.
              front()
              Anchor.Bottom { back() }
            } else {
              back()
              front()
            }
          },
      )
    }

    waitUntil(timeoutMillis = TIMEOUT) { mapState.presentation != null }
    val presentation = assertNotNull(mapState.presentation, "the map never published a lease")
    val size = onRoot().fetchSemanticsNode().size
    val centerDp = with(density) { DpOffset((size.width / 2).toDp(), (size.height / 2).toDp()) }

    // A layer is only dispatched to if a rendered query hits it, and only a rendered frame of the
    // parsed source populates that. Both layers must be hittable, or the assertions prove nothing.
    waitUntil(timeoutMillis = TIMEOUT) {
      listOf(FRONT, BACK).all { id ->
        runBlocking { presentation.queryRenderedFeatures(offset = centerDp, layerIds = setOf(id)) }
          .isNotEmpty()
      }
    }

    body(Offset(size.width / 2f, size.height / 2f))
  }

  private companion object {
    const val TIMEOUT = 30_000L

    /** Zoomed in far enough that [WORLD_POLYGON] covers the viewport edge to edge. */
    const val START_ZOOM = 2.0

    const val FRONT = "front"
    const val BACK = "back"

    val WORLD_POLYGON =
      """
      {
        "type": "Feature",
        "properties": {},
        "geometry": {
          "type": "Polygon",
          "coordinates": [[[-170, -80], [170, -80], [170, 80], [-170, 80], [-170, -80]]]
        }
      }
      """
        .trimIndent()
  }
}
