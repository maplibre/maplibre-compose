package org.maplibre.compose.layers

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.DpOffset
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.interaction.ClickResult
import org.maplibre.compose.map.MapRuntimeOptions
import org.maplibre.compose.map.MapState
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.rememberMapState
import org.maplibre.compose.mlnffi.FfiTestPlatform
import org.maplibre.compose.mlnffi.performMouseInputOnUiThread
import org.maplibre.compose.mlnffi.performTouchInputOnUiThread
import org.maplibre.compose.mlnffi.runFfiComposeUiTest
import org.maplibre.compose.mlnffi.setFfiTestMapContent
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.style.BaseStyle
import org.maplibre.spatialk.geojson.Position

/**
 * Two overlapping fill layers cover the viewport. Click dispatch must follow native style order,
 * not composition order. Anchors let the tests vary these orders independently.
 *
 * Regression coverage for #69, fixed by #93. A rendered map is required to query layer coverage.
 */
@OptIn(ExperimentalTestApi::class)
class LayerClickOrderTest {

  private val cache = FfiTestPlatform.createCacheFile()

  private val runtimeOptions = MapRuntimeOptions { cacheFile = cache }

  /** Which layers were offered the event, in the order the map offered them. */
  private val clicked = mutableListOf<String>()
  private val longClicked = mutableListOf<String>()

  @AfterTest
  fun cleanUp() {
    FfiTestPlatform.deleteCacheFile(cache)
  }

  @Test
  fun a_click_goes_to_the_layer_in_front() =
    runLayerClickTest(composeFrontLayerFirst = false) { center ->
      performMouseInputOnUiThread(onRoot()) { click(center) }

      waitUntil(timeoutMillis = Timeout) { clicked.isNotEmpty() }
      waitForIdle()
      assertEquals(listOf(Front), clicked)
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
      performMouseInputOnUiThread(onRoot()) { click(center) }

      waitUntil(timeoutMillis = Timeout) { clicked.isNotEmpty() }
      waitForIdle()
      assertEquals(listOf(Front), clicked)
    }

  @Test
  fun a_click_the_front_layer_passes_falls_through_to_the_layer_behind() =
    runLayerClickTest(composeFrontLayerFirst = true, frontResult = ClickResult.Pass) { center ->
      performMouseInputOnUiThread(onRoot()) { click(center) }

      waitUntil(timeoutMillis = Timeout) { clicked.size == 2 }
      waitForIdle()
      assertEquals(listOf(Front, Back), clicked)
    }

  @Test
  fun a_long_click_goes_to_the_layer_in_front_too() =
    runLayerClickTest(composeFrontLayerFirst = true) { center ->
      val map = onRoot()
      performTouchInputOnUiThread(map) { down(0, center) }
      mainClock.advanceTimeBy(1_000)
      waitUntil(timeoutMillis = Timeout) { longClicked.isNotEmpty() }
      performTouchInputOnUiThread(map) { up(0) }
      waitForIdle()

      assertEquals(listOf(Front), longClicked)
      assertEquals(emptyList<String>(), clicked, "the long click also reported a click")
    }

  @Test
  fun a_long_click_the_front_layer_passes_falls_through_to_the_layer_behind() =
    runLayerClickTest(composeFrontLayerFirst = true, frontResult = ClickResult.Pass) { center ->
      val map = onRoot()
      performTouchInputOnUiThread(map) { down(0, center) }
      mainClock.advanceTimeBy(1_000)
      waitUntil(timeoutMillis = Timeout) { longClicked.size == 2 }
      performTouchInputOnUiThread(map) { up(0) }
      waitForIdle()

      assertEquals(listOf(Front, Back), longClicked)
    }

  /**
   * Composes two fill layers of the same world-covering polygon, waits until both are rendered and
   * queryable, then runs [body] with the centre of the map.
   *
   * [composeFrontLayerFirst] only changes the composition; either way [Front] ends up in front of
   * [Back] in the style. [frontResult] is what [Front]'s handlers return, so a test can either stop
   * the event there or let it fall through.
   */
  private fun runLayerClickTest(
    composeFrontLayerFirst: Boolean,
    frontResult: ClickResult = ClickResult.Consume,
    body: ComposeUiTest.(center: Offset) -> Unit,
  ) = runFfiComposeUiTest {
    lateinit var mapState: MapState
    lateinit var scope: CoroutineScope

    setFfiTestMapContent(runtimeOptions) {
      scope = rememberCoroutineScope()
      mapState =
        rememberMapState(
          initialCameraPosition = CameraPosition(center = Position(0.0, 0.0), zoom = StartZoom),
          baseStyle = BaseStyle.Empty,
        ) {
          val source = rememberGeoJsonSource(data = GeoJsonData.JsonString(WorldPolygon))

          val front: @Composable () -> Unit = {
            FillLayer(
              id = Front,
              source = source,
              color = const(Color.Red),
              onClick = {
                clicked += Front
                frontResult
              },
              onLongClick = {
                longClicked += Front
                frontResult
              },
            )
          }
          val back: @Composable () -> Unit = {
            FillLayer(
              id = Back,
              source = source,
              color = const(Color.Blue),
              onClick = {
                clicked += Back
                ClickResult.Consume
              },
              onLongClick = {
                longClicked += Back
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
        }
      MaplibreMap(state = mapState, modifier = Modifier.fillMaxSize())
    }

    waitUntil(timeoutMillis = Timeout) { mapState.currentMapAttachment != null }
    assertNotNull(mapState.currentMapAttachment, "the map never published a lease")
    val size = onRoot().fetchSemanticsNode().size
    val centerDp = with(density) { DpOffset((size.width / 2).toDp(), (size.height / 2).toDp()) }

    // A layer is only dispatched to if a rendered query hits it, and only a rendered frame of the
    // parsed source populates that. Both layers must be hittable, or the assertions prove nothing.
    // Queries can await the first viewport. Keep the test clock running so style installation
    // and presentation can publish it while the query is suspended.
    val layersHittable = scope.async {
      while (
        !listOf(Front, Back).all { id ->
          mapState.queryRenderedFeatures(offset = centerDp, layerIds = setOf(id)).isNotEmpty()
        }
      ) {
        withFrameNanos {}
      }
    }
    waitUntil(timeoutMillis = Timeout) { layersHittable.isCompleted }
    layersHittable.await()

    body(Offset(size.width / 2f, size.height / 2f))
  }

  private companion object {
    const val Timeout = 30_000L

    /** Zoomed in far enough that [WorldPolygon] covers the viewport edge to edge. */
    const val StartZoom = 2.0

    const val Front = "front"
    const val Back = "back"

    val WorldPolygon =
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
