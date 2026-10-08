@file:OptIn(ExperimentalAtomicApi::class)

package org.maplibre.compose.map

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlinx.coroutines.awaitCancellation
import kotlinx.serialization.json.JsonObject
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.layers.Anchor
import org.maplibre.compose.layers.BackgroundLayer
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.layers.FillLayer
import org.maplibre.compose.mlnffi.FfiTestPlatform
import org.maplibre.compose.mlnffi.TestLatch
import org.maplibre.compose.mlnffi.runFfiComposeUiTest
import org.maplibre.compose.mlnffi.setFfiTestMapContent
import org.maplibre.compose.resource.MapResourceProvider
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.style.BaseStyle
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.Geometry
import org.maplibre.spatialk.geojson.Point
import org.maplibre.spatialk.geojson.Position
import org.maplibre.spatialk.geojson.dsl.addFeature
import org.maplibre.spatialk.geojson.dsl.buildFeatureCollection

/**
 * Rotating the base style with user content composed over it: every composed source and layer has
 * to be re-added, in order, against a base style whose own layers are different.
 */
@OptIn(ExperimentalTestApi::class)
class MlnFfiStyleSwitchTest {

  private val cache = FfiTestPlatform.createCacheFile()

  private val runtimeOptions = MapRuntimeOptions {
    cacheFile = cache
  }

  @AfterTest
  fun cleanUp() {
    FfiTestPlatform.deleteCacheFile(cache)
  }

  @Test
  fun rotating_the_base_style_with_content_composed_over_it() =
    // A different base-style anchor per style, so the anchor changes in the same recomposition as
    // the style itself.
    rotateStyles { it.anchor }

  @Test
  fun one_predicate_anchor_lands_below_the_labels_of_every_style() =
    // The label layers have different IDs per style, so one anchor has to resolve against each.
    rotateStyles { Anchor.Below { layer -> layer.type == "symbol" } }

  private fun rotateStyles(anchorFor: (DemoStyle) -> Anchor) = runFfiComposeUiTest {
    withTestRuntime(runtimeOptions) { runtime ->
      var style by mutableStateOf(Styles[0])
      var extraLayer by mutableStateOf(false)
      val state =
        runtime.createMapState(baseStyle = Styles[0].base) {
          val points = rememberGeoJsonSource(data = GeoJsonData.Features(pointAt(longitude = 0.0)))
          // Two layers on one source at different anchors, so the re-add order matters.
          CircleLayer(id = "user-circles", source = points, color = const(Color.Red))
          Anchor.At(anchorFor(style)) {
            FillLayer(id = "user-fill", source = points, color = const(Color.Blue))
            // Comes and goes across the rotation, covering removal of a layer that was added
            // against
            // a different base style.
            if (extraLayer) {
              FillLayer(id = "user-extra", source = points, color = const(Color.Green))
            }
          }
        }

      setFfiTestMapContent(runtimeOptions) {
        MaplibreMap(modifier = Modifier, state = state)
      }

      // Each style finishes loading before the next is chosen; switching mid-load is a separate
      // race
      // this test deliberately does not cover.
      waitUntil(timeoutMillis = SettleTimeoutMillis) {
        state.currentMapAttachment != null && state.style.loadState == StyleLoadState.Ready
      }
      val session = requireNotNull(state.currentMapAttachment).adapter as MlnFfiMapSession
      var identity = assertNotNull(session.loadedStyleIdentity)
      assertStyleLayers(session, style, extraLayer)

      repeat(Rotations) { round ->
        runOnUiThread {
          style = Styles[(round + 1) % Styles.size]
          extraLayer = !extraLayer
          state.style.asMutable!!.baseStyle = style.base
        }
        waitUntil(timeoutMillis = SettleTimeoutMillis) {
          state.style.loadState == StyleLoadState.Ready && session.loadedStyleIdentity != identity
        }
        val replacementIdentity = assertNotNull(session.loadedStyleIdentity)
        assertNotSame(identity, replacementIdentity)
        identity = replacementIdentity
        assertStyleLayers(session, style, extraLayer)
      }
    }
  }

  @Test
  fun recreating_an_anchored_layer_while_switching_the_base_style() = runFfiComposeUiTest {
    withTestRuntime(runtimeOptions) { runtime ->
      var style by mutableStateOf(SlotStyles[0])
      var sourceLayer by mutableStateOf("places")
      val state =
        runtime.createMapState(baseStyle = SlotStyles[0]) {
          val points = rememberGeoJsonSource(data = GeoJsonData.Features(pointAt(longitude = 0.0)))
          Anchor.Below("base-slot") {
            FillLayer(
              id = "user-anchored",
              source = points,
              sourceLayer = sourceLayer,
              color = const(Color.Blue),
            )
          }
        }

      setFfiTestMapContent(runtimeOptions) {
        MaplibreMap(modifier = Modifier, state = state)
      }

      waitUntil(timeoutMillis = SettleTimeoutMillis) {
        state.currentMapAttachment != null && state.style.loadState == StyleLoadState.Ready
      }
      val session = requireNotNull(state.currentMapAttachment).adapter as MlnFfiMapSession
      fun slotLayers(): List<String> = session.currentStyleLayerIds().filter { it in SlotLayerIds }
      waitUntil(timeoutMillis = SettleTimeoutMillis) {
        slotLayers() == listOf("bg-a", "user-anchored", "base-slot")
      }

      runOnUiThread {
        style = SlotStyles[1]
        sourceLayer = "roads"
        state.style.asMutable!!.baseStyle = style
      }
      waitUntil(timeoutMillis = SettleTimeoutMillis) {
        state.style.loadState == StyleLoadState.Ready
      }
      waitUntil(timeoutMillis = SettleTimeoutMillis) {
        slotLayers() == listOf("bg-b", "user-anchored", "base-slot")
      }
    }
  }

  @Test
  fun a_stalled_style_is_superseded_before_composing_the_latest_content() = runFfiComposeUiTest {
    val styleBStarted = TestLatch(1)
    val styleBCancelled = TestLatch(1)
    withTestRuntime(
      from = runtimeOptions,
      configure = {
        resourceProvider =
          MapResourceProvider("held") { request ->
            when (request.url) {
              BStyleUrl -> {
                styleBStarted.countDown()
                try {
                  awaitCancellation()
                } finally {
                  styleBCancelled.countDown()
                }
              }
              CStyleUrl -> StyleCJson.encodeToByteArray()
              else -> error("Unexpected resource request for ${request.url}")
            }
          }
      },
    ) { runtime ->
      var showLatestLayer by mutableStateOf(false)
      val state =
        runtime.createMapState(baseStyle = InitialStyle) {
          if (showLatestLayer) {
            Anchor.Below("base-c") {
              BackgroundLayer(id = "user-latest", color = const(Color.Blue))
            }
          }
        }

      setFfiTestMapContent(runtimeOptions) {
        MaplibreMap(modifier = Modifier, state = state)
      }

      waitUntil(timeoutMillis = SettleTimeoutMillis) {
        state.currentMapAttachment != null && state.style.loadState == StyleLoadState.Ready
      }
      val session = requireNotNull(state.currentMapAttachment).adapter as MlnFfiMapSession

      runOnUiThread {
        state.style.asMutable!!.baseStyle = BaseStyle.Uri(BStyleUrl)
      }
      waitUntil(timeoutMillis = SettleTimeoutMillis) {
        styleBStarted.count == 0L
      }

      runOnUiThread {
        showLatestLayer = true
        state.style.asMutable!!.baseStyle = BaseStyle.Uri(CStyleUrl)
      }

      waitUntil(timeoutMillis = SettleTimeoutMillis) {
        styleBCancelled.count == 0L &&
          state.style.loadState == StyleLoadState.Ready &&
          "user-latest" in session.currentStyleLayerIds()
      }

      val layers =
        session.currentStyleLayerIds().filter { it == "user-latest" || it.startsWith("base-") }
      assertEquals(listOf("user-latest", "base-c"), layers)
    }
  }

  private fun androidx.compose.ui.test.ComposeUiTest.assertStyleLayers(
    session: MlnFfiMapSession,
    style: DemoStyle,
    extraLayer: Boolean,
  ) {
    val expected = buildList {
      add(style.baseLayerIds.first())
      add("user-fill")
      if (extraLayer) add("user-extra")
      add(style.baseLayerIds.last())
      add("user-circles")
    }
    fun relevantLayers(): List<String> =
      session.currentStyleLayerIds().filter { it in RelevantLayerIds }

    waitUntil(timeoutMillis = SettleTimeoutMillis) { relevantLayers() == expected }
    assertEquals(expected, relevantLayers(), "live style layer order")
  }

  /** A style and the base-style layer content anchors itself below, as the demo pairs them. */
  private data class DemoStyle(
    val base: BaseStyle,
    val anchor: Anchor,
    val baseLayerIds: List<String>,
  )

  private fun pointAt(longitude: Double): FeatureCollection<Geometry, JsonObject?> =
    buildFeatureCollection {
      addFeature(geometry = Point(Position(longitude = longitude, latitude = 0.0)))
    }

  private companion object {
    const val SettleTimeoutMillis = 30_000L
    const val BStyleUrl = "held://style-b"
    const val CStyleUrl = "held://style-c"

    const val EmptySourceJson =
      """{"type":"geojson","data":{"type":"FeatureCollection","features":[]}}"""

    const val StyleCJson =
      """{"version":8,"sources":{},"layers":[{"id":"base-c","type":"background"}]}"""

    val InitialStyle =
      BaseStyle.Json(
        """{"version":8,"sources":{},"layers":[{"id":"base-initial","type":"background"}]}"""
      )

    /** Enough rounds that a fault which needs a second or third switch still shows up. */
    const val Rotations = 6

    val RelevantLayerIds =
      setOf(
        "bg-a",
        "labels-a",
        "bg-b",
        "labels-b",
        "user-fill",
        "user-extra",
        "user-circles",
      )

    val SlotLayerIds = setOf("bg-a", "bg-b", "base-slot", "user-anchored")

    val SlotStyles =
      listOf(
        BaseStyle.Json(
          """
          {"version":8,"sources":{},"layers":[
            {"id":"bg-a","type":"background","paint":{"background-color":"#eee"}},
            {"id":"base-slot","type":"background","paint":{"background-color":"#e0e0e0"}}
          ]}
          """
        ),
        BaseStyle.Json(
          """
          {"version":8,"sources":{},"layers":[
            {"id":"bg-b","type":"background","paint":{"background-color":"#ddd"}},
            {"id":"base-slot","type":"background","paint":{"background-color":"#cccccc"}}
          ]}
          """
        ),
      )

    /**
     * Styles with different layer sets, so a re-add lands against a different base each time.
     * Inline rather than remote, so the test does not need the network.
     */
    val Styles =
      listOf(
        DemoStyle(
          base =
            BaseStyle.Json(
              """
              {"version":8,"sources":{"empty":$EmptySourceJson},"layers":[
                {"id":"bg-a","type":"background","paint":{"background-color":"#eee"}},
                {"id":"labels-a","type":"symbol","source":"empty"}
              ]}
              """
            ),
          anchor = Anchor.Below("labels-a"),
          baseLayerIds = listOf("bg-a", "labels-a"),
        ),
        DemoStyle(
          base =
            BaseStyle.Json(
              """
              {"version":8,"sources":{"empty":$EmptySourceJson},"layers":[
                {"id":"bg-b","type":"background","paint":{"background-color":"#ddd"}},
                {"id":"labels-b","type":"symbol","source":"empty"}
              ]}
              """
            ),
          anchor = Anchor.Below("labels-b"),
          baseLayerIds = listOf("bg-b", "labels-b"),
        ),
      )
  }
}
