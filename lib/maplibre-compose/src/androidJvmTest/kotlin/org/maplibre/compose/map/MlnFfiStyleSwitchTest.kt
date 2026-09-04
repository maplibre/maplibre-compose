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
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.layers.Anchor
import org.maplibre.compose.layers.BackgroundLayer
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.layers.FillLayer
import org.maplibre.compose.mlnffi.FfiTestPlatform
import org.maplibre.compose.mlnffi.MlnFfiRuntimeOptions
import org.maplibre.compose.mlnffi.TestLatch
import org.maplibre.compose.mlnffi.runFfiComposeUiTest
import org.maplibre.compose.mlnffi.setFfiTestMapContent
import org.maplibre.compose.resource.MlnFfiResourceProvider
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.StyleComposition
import org.maplibre.nativeffi.resource.ResourceResponse
import org.maplibre.nativeffi.resource.ResourceResponseStatus
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

  private val cacheFile = FfiTestPlatform.createCacheFile()

  private val runtimeOptions = MapRuntimeOptions(cacheFile = cacheFile)

  @AfterTest
  fun cleanUp() {
    FfiTestPlatform.deleteCacheFile(cacheFile)
  }

  @Test
  fun rotating_the_base_style_with_content_composed_over_it() = runFfiComposeUiTest {
    val runtime = createMapRuntime(runtimeOptions)
    var style by mutableStateOf(STYLES[0])
    var extraLayer by mutableStateOf(false)
    val composition = StyleComposition {
      val points = rememberGeoJsonSource(data = GeoJsonData.Features(pointAt(longitude = 0.0)))
      // Two layers on one source at different anchors, so the re-add order matters.
      CircleLayer(id = "user-circles", source = points, color = const(Color.Red))
      // A different base-style anchor per style, so the anchor changes in the same
      // recomposition as the style itself.
      Anchor.At(style.anchor) {
        FillLayer(id = "user-fill", source = points, color = const(Color.Blue))
        // Comes and goes across the rotation, covering removal of a layer that was added while
        // its anchor was unresolvable.
        if (extraLayer) {
          FillLayer(id = "user-extra", source = points, color = const(Color.Green))
        }
      }
    }
    val state = runtime.createMapState(baseStyle = STYLES[0].base, styleComposition = composition)

    setFfiTestMapContent(runtimeOptions) {
      MaplibreMap(modifier = Modifier, state = state)
    }

    // Each style finishes loading before the next is chosen; switching mid-load is a separate race
    // this test deliberately does not cover.
    waitUntil(timeoutMillis = SETTLE_TIMEOUT_MILLIS) {
      state.currentMapAttachment != null && state.style.loadState == StyleLoadState.Ready
    }
    val session = requireNotNull(state.currentMapAttachment).adapter as MlnFfiMapSession
    var identity = assertNotNull(session.loadedStyleIdentity)
    assertStyleLayers(session, style, extraLayer)

    repeat(ROTATIONS) { round ->
      runOnUiThread {
        style = STYLES[(round + 1) % STYLES.size]
        extraLayer = !extraLayer
        state.style.baseStyle = style.base
      }
      waitUntil(timeoutMillis = SETTLE_TIMEOUT_MILLIS) {
        state.style.loadState == StyleLoadState.Ready && session.loadedStyleIdentity != identity
      }
      val replacementIdentity = assertNotNull(session.loadedStyleIdentity)
      assertNotSame(identity, replacementIdentity)
      identity = replacementIdentity
      assertStyleLayers(session, style, extraLayer)
    }

    runtime.close()
    runtime.awaitClosed()
  }

  @Test
  fun recreating_a_replacement_layer_while_switching_the_base_style() = runFfiComposeUiTest {
    val runtime = createMapRuntime(runtimeOptions)
    var style by mutableStateOf(REPLACEMENT_STYLES[0])
    var sourceLayer by mutableStateOf("places")
    var showReplacement by mutableStateOf(true)
    val composition = StyleComposition {
      val points = rememberGeoJsonSource(data = GeoJsonData.Features(pointAt(longitude = 0.0)))
      if (showReplacement) {
        Anchor.Replace("base-slot") {
          FillLayer(
            id = "user-replacement",
            source = points,
            sourceLayer = sourceLayer,
            color = const(Color.Blue),
          )
        }
      }
    }
    val state =
      runtime.createMapState(
        baseStyle = REPLACEMENT_STYLES[0],
        styleComposition = composition,
      )

    setFfiTestMapContent(runtimeOptions) {
      MaplibreMap(modifier = Modifier, state = state)
    }

    waitUntil(timeoutMillis = SETTLE_TIMEOUT_MILLIS) {
      state.currentMapAttachment != null && state.style.loadState == StyleLoadState.Ready
    }
    val session = requireNotNull(state.currentMapAttachment).adapter as MlnFfiMapSession
    fun replacementLayers(): List<String> =
      session.currentStyleLayerIds().filter { it in REPLACEMENT_LAYER_IDS }
    waitUntil(timeoutMillis = SETTLE_TIMEOUT_MILLIS) {
      replacementLayers() == listOf("bg-a", "user-replacement")
    }

    runOnUiThread {
      style = REPLACEMENT_STYLES[1]
      sourceLayer = "roads"
      state.style.baseStyle = style
    }
    waitUntil(timeoutMillis = SETTLE_TIMEOUT_MILLIS) {
      state.style.loadState == StyleLoadState.Ready
    }
    waitUntil(timeoutMillis = SETTLE_TIMEOUT_MILLIS) {
      replacementLayers() == listOf("bg-b", "user-replacement")
    }
    runOnUiThread { showReplacement = false }
    waitUntil(timeoutMillis = SETTLE_TIMEOUT_MILLIS) {
      replacementLayers() == listOf("bg-b", "base-slot")
    }
    runtime.close()
    runtime.awaitClosed()
  }

  @Test
  fun a_late_style_load_cannot_receive_content_for_the_latest_style() = runFfiComposeUiTest {
    // Common tests own request identity and reconciliation order. This test keeps only the native
    // boundary where a late load can expose an obsolete style to a composed write.
    val resources = BlockingStyleResources()
    val options =
      MlnFfiRuntimeOptions(
        cacheFile = cacheFile,
        maximumCacheSizeBytes = null,
        resourceProviderFactory = { getLogger, config ->
          MlnFfiResourceProvider(
            getLogger = getLogger,
            config = config,
            read = resources::read,
            passThroughNetwork = false,
          )
        },
      )
    val runtime = createNativeMapRuntime(options)
    var showLatestLayer by mutableStateOf(false)
    val composition = StyleComposition {
      if (showLatestLayer) {
        Anchor.Below("base-c") {
          BackgroundLayer(id = "user-latest", color = const(Color.Blue))
        }
      }
    }
    val state = runtime.createMapState(baseStyle = INITIAL_STYLE, styleComposition = composition)

    setFfiTestMapContent(runtimeOptions) {
      MaplibreMap(modifier = Modifier, state = state)
    }

    waitUntil(timeoutMillis = SETTLE_TIMEOUT_MILLIS) {
      state.currentMapAttachment != null && state.style.loadState == StyleLoadState.Ready
    }
    val session = requireNotNull(state.currentMapAttachment).adapter as MlnFfiMapSession

    runOnUiThread {
      state.style.baseStyle = BaseStyle.Uri(B_STYLE_URL)
    }
    waitUntil(timeoutMillis = SETTLE_TIMEOUT_MILLIS) {
      resources.styleBStarted.count == 0L
    }

    runOnUiThread {
      showLatestLayer = true
      state.style.baseStyle = BaseStyle.Uri(C_STYLE_URL)
    }
    resources.releaseStyleB.countDown()

    waitUntil(timeoutMillis = SETTLE_TIMEOUT_MILLIS) {
      state.style.loadState == StyleLoadState.Ready &&
        "user-latest" in session.currentStyleLayerIds()
    }

    val layers = session.currentStyleLayerIds()
    assertTrue("base-c" in layers, "the latest base style must be loaded")
    assertTrue("base-b" !in layers, "the superseded base style must not remain loaded")
    assertTrue("user-latest" in layers, "content for the latest style must be installed")
    runtime.close()
    runtime.awaitClosed()
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
      session.currentStyleLayerIds().filter { it in RELEVANT_LAYER_IDS }

    waitUntil(timeoutMillis = SETTLE_TIMEOUT_MILLIS) { relevantLayers() == expected }
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

  private class BlockingStyleResources {
    val styleBStarted = TestLatch(1)
    val releaseStyleB = TestLatch(1)

    fun read(url: String, requestedUrl: String): ResourceResponse {
      val body =
        when (url) {
          B_STYLE_URL -> {
            styleBStarted.countDown()
            check(releaseStyleB.await(WAIT_SECONDS * 1_000)) {
              "style B was not released"
            }
            STYLE_B_JSON
          }
          C_STYLE_URL -> STYLE_C_JSON
          else -> error("Unexpected resource request for $url (requested as $requestedUrl)")
        }
      return ResourceResponse(ResourceResponseStatus.OK).also {
        it.bytes = body.encodeToByteArray()
        it.mustRevalidate = false
      }
    }
  }

  private companion object {
    const val SETTLE_TIMEOUT_MILLIS = 30_000L
    const val WAIT_SECONDS = 10L
    const val B_STYLE_URL = "https://style-b.test/style.json"
    const val C_STYLE_URL = "https://style-c.test/style.json"

    const val STYLE_B_JSON =
      """{"version":8,"sources":{},"layers":[{"id":"base-b","type":"background"}]}"""
    const val STYLE_C_JSON =
      """{"version":8,"sources":{},"layers":[{"id":"base-c","type":"background"}]}"""

    val INITIAL_STYLE =
      BaseStyle.Json(
        """{"version":8,"sources":{},"layers":[{"id":"base-initial","type":"background"}]}"""
      )

    /** Enough rounds that a fault which needs a second or third switch still shows up. */
    const val ROTATIONS = 6

    val RELEVANT_LAYER_IDS =
      setOf(
        "base-initial",
        "base-b",
        "base-c",
        "bg-a",
        "labels-a",
        "bg-b",
        "labels-b",
        "user-fill",
        "user-extra",
        "user-circles",
      )

    val REPLACEMENT_LAYER_IDS = setOf("bg-a", "bg-b", "base-slot", "user-replacement")

    val REPLACEMENT_STYLES =
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
    val STYLES =
      listOf(
        DemoStyle(
          base =
            BaseStyle.Json(
              """
              {"version":8,"sources":{},"layers":[
                {"id":"bg-a","type":"background","paint":{"background-color":"#eee"}},
                {"id":"labels-a","type":"background","paint":{"background-color":"#e0e0e0"}}
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
              {"version":8,"sources":{},"layers":[
                {"id":"bg-b","type":"background","paint":{"background-color":"#ddd"}},
                {"id":"labels-b","type":"background","paint":{"background-color":"#cccccc"}}
              ]}
              """
            ),
          anchor = Anchor.Below("labels-b"),
          baseLayerIds = listOf("bg-b", "labels-b"),
        ),
      )
  }
}
