package org.maplibre.compose.gljs

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.unit.dp
import kotlin.js.Promise
import kotlin.js.js
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.browser.window
import org.maplibre.compose.layers.RasterLayer
import org.maplibre.compose.map.GlJsMapSession
import org.maplibre.compose.map.MapRuntimeOptions
import org.maplibre.compose.map.MapState
import org.maplibre.compose.map.MapStyleState
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.StyleLoadState
import org.maplibre.compose.map.createMapRuntime
import org.maplibre.compose.map.rememberMapState
import org.maplibre.compose.sources.RasterSource
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.LocalStyleNode
import org.maplibre.compose.style.StyleComposition
import org.maplibre.compose.style.StyleIdentity

@OptIn(ExperimentalTestApi::class)
class BrowserStyleStateTest {

  private fun styleWith(name: String, sourceId: String) =
    BaseStyle.Json(
      """
      {
        "version": 8,
        "name": "$name",
        "sources": {
          "$sourceId": {
            "type": "geojson",
            "attribution": "$name attribution",
            "data": {"type": "FeatureCollection", "features": []}
          }
        },
        "layers": [{"id": "bg", "type": "background", "paint": {"background-color": "#000000"}}]
      }
      """
        .trimIndent()
    )

  private val tileJsonStyle =
    BaseStyle.Json(
      """
      {
        "version": 8,
        "name": "tilejson",
        "sources": { "remote": { "type": "vector", "url": "https://tilejson.test/x.json" } },
        "layers": [{"id": "bg", "type": "background", "paint": {"background-color": "#000000"}}]
      }
      """
        .trimIndent()
    )

  /** Delays the TileJSON so the source's attribution is not readable until well after load. */
  private fun installSlowTileJson(): () -> Unit {
    val global = js("window")
    val original = global.fetch
    global.fetch = { input: dynamic, init: dynamic ->
      val url = if (jsTypeOf(input) == "string") input as String else input.url as String
      if (url.contains("tilejson.test")) {
        Promise<dynamic> { resolve, _ ->
          window.setTimeout({ resolve(makeJsonResponse(TILE_JSON)) }, 250)
        }
      } else {
        original.call(global, input, init)
      }
    }
    return { global.fetch = original }
  }

  /**
   * Holds the TileJSON response until the test has observed the source's initial state. MapLibre GL
   * JS 6 awaits `transformRequest` before `fetch`, so the request is not in flight in the same turn
   * as `addSource`.
   */
  private fun installDeferredTileJson(): DeferredTileJson {
    val global = js("window")
    val original = global.fetch
    var resolveTileJson: ((dynamic) -> Unit)? = null
    global.fetch = { input: dynamic, init: dynamic ->
      val url = if (jsTypeOf(input) == "string") input as String else input.url as String
      if (url.contains("tilejson.test")) {
        Promise<dynamic> { resolve, _ -> resolveTileJson = resolve }
      } else {
        original.call(global, input, init)
      }
    }
    return DeferredTileJson(
      restore = { global.fetch = original },
      isRequested = { resolveTileJson != null },
      resolve = {
        checkNotNull(resolveTileJson) { "MapLibre has not requested the TileJSON" }
          .invoke(makeJsonResponse(TILE_JSON))
      },
    )
  }

  private class DeferredTileJson(
    val restore: () -> Unit,
    val isRequested: () -> Boolean,
    val resolve: () -> Unit,
  )

  private fun makeJsonResponse(body: String): dynamic =
    js("new Response(body, { status: 200, headers: { 'content-type': 'application/json' } })")

  @Test
  fun a_detached_web_map_keeps_its_desired_style_pending_and_replays_it(): Promise<*> =
    runBrowserMapTest {
      val runtime = createMapRuntime(MapRuntimeOptions())
      val state = runtime.createMapState(initialBaseStyle = STYLE_A)
      val presented = mutableStateOf(true)
      val useLatestRevision = mutableStateOf(false)
      val composition = StyleComposition {
        val suffix = if (useLatestRevision.value) "latest" else "initial"
        RasterLayer(
          id = "$suffix-overlay",
          source = RasterSource("$suffix-source", "https://example.invalid/$suffix.json"),
          visible = true,
        )
      }

      setBrowserMapContent {
        if (presented.value) MaplibreMap(state, styleComposition = composition)
      }
      waitUntilMap("style A to become ready") {
        state.presentation != null && state.style.loadState == StyleLoadState.Ready
      }
      val initialSession = requireNotNull(state.presentation).adapter as GlJsMapSession
      assertTrue(
        initialSession.engineMapForTest()?.getStyle()?.layers?.any { it.id == "initial-overlay" } ==
          true
      )

      runOnIdle { presented.value = false }
      waitUntilMap("the Web map to detach") { state.presentation == null }
      runOnIdle {
        useLatestRevision.value = true
        state.style.baseStyle = STYLE_B
      }

      assertEquals(STYLE_B, state.style.baseStyle)
      assertEquals(StyleLoadState.Pending, state.style.loadState)

      val layerAdditions = mutableListOf<String>()
      val mapPrototype = org.maplibre.compose.gljs.MaplibreMap::class.js.asDynamic().prototype
      val originalAddLayer = mapPrototype.addLayer
      val wrapAddLayer =
        js(
          """(function(original, record) {
            return function(layer, before) {
              record(layer.id);
              return original.call(this, layer, before);
            };
          })"""
        )
      mapPrototype.addLayer = wrapAddLayer(originalAddLayer) { id: String -> layerAdditions += id }
      try {
        runOnIdle { presented.value = true }
        waitUntilMap("style B to load on the replacement map") {
          state.presentation != null && state.style.loadState == StyleLoadState.Ready
        }
        val session = requireNotNull(state.presentation).adapter as GlJsMapSession

        assertTrue(layerAdditions.indexOf("initial-overlay") >= 0)
        assertTrue(
          layerAdditions.indexOf("latest-overlay") > layerAdditions.indexOf("initial-overlay")
        )
        assertEquals(
          listOf("b", "latest-overlay"),
          requireNotNull(session.engineMapForTest()).getStyle().layers.map { it.id },
        )
        assertFalse(
          requireNotNull(session.engineMapForTest()).getStyle().layers.any {
            it.id == "initial-overlay"
          }
        )
      } finally {
        mapPrototype.addLayer = originalAddLayer
      }

      runtime.close()
      runtime.awaitClosed()
    }

  @Test
  fun a_web_presentation_waits_for_a_viewport_and_survives_style_failure(): Promise<*> =
    runBrowserMapTest {
      val runtime = createMapRuntime(MapRuntimeOptions())
      val state = runtime.createMapState(initialBaseStyle = STYLE_A)
      val size = mutableStateOf(0.dp)

      setBrowserMapContent { MaplibreMap(state, modifier = Modifier.size(size.value)) }
      waitForIdle()

      assertNull(state.presentation)
      assertEquals(StyleLoadState.Pending, state.style.loadState)

      runOnIdle { size.value = 128.dp }
      waitUntilMap("the attached style to become ready") {
        state.presentation != null && state.style.loadState == StyleLoadState.Ready
      }
      val presentation = requireNotNull(state.presentation)
      val session = requireNotNull(state.presentation).adapter as GlJsMapSession

      assertNotNull(session.engineMapForTest())
      assertTrue(session.canPresentFrames)

      runOnIdle { state.style.baseStyle = INVALID_STYLE }
      waitUntilMap("the replacement style request to fail") {
        state.style.loadState is StyleLoadState.Failed
      }

      assertTrue(state.presentation === presentation)
      assertTrue(presentation.isValid)
      assertFalse(session.canPresentFrames, "the failed map surface must remain hidden")

      runtime.close()
      runtime.awaitClosed()
    }

  @Test
  fun a_source_reports_the_attribution_its_tilejson_carries(): Promise<*> = runBrowserMapTest {
    val restoreFetch = installSlowTileJson()
    try {
      var styleState: MapStyleState? = null
      var mapState: MapState? = null
      setBrowserMapContent {
        val current = rememberMapState(initialBaseStyle = tileJsonStyle)
        mapState = current
        styleState = current.style
        MaplibreMap(state = current, modifier = Modifier)
      }
      waitUntilMap("the map to report that it finished loading") {
        mapState?.style?.loadState == StyleLoadState.Ready
      }

      assertEquals(
        listOf("fetched attribution"),
        styleState?.sources?.values?.map { it.attributionHtml },
        "loading is only finished once a source's TileJSON has arrived; the attribution UI reads " +
          "this the moment it is told",
      )
    } finally {
      restoreFetch()
    }
  }

  @Test
  fun switching_to_a_tilejson_style_keeps_the_attribution(): Promise<*> = runBrowserMapTest {
    val restoreFetch = installSlowTileJson()
    try {
      val current = mutableStateOf(styleWith("first", "first-source"))
      var styleState: MapStyleState? = null
      var mapState: MapState? = null
      setBrowserMapContent {
        val logicalMap = rememberMapState(initialBaseStyle = current.value)
        mapState = logicalMap
        styleState = logicalMap.style
        SideEffect { logicalMap.style.baseStyle = current.value }
        MaplibreMap(state = logicalMap, modifier = Modifier)
      }
      waitUntilMap("the first style to load") {
        mapState?.style?.loadState == StyleLoadState.Ready
      }

      current.value = tileJsonStyle
      waitUntilMap("the switched style's attribution to be reported") {
        styleState?.sources?.values?.map { it.attributionHtml } == listOf("fetched attribution")
      }
    } finally {
      restoreFetch()
    }
  }

  @Test
  fun a_source_added_after_load_reports_late_tilejson_attribution(): Promise<*> =
    runBrowserMapTest {
      val tileJson = installDeferredTileJson()
      try {
        val showLateSource = mutableStateOf(false)
        val source = RasterSource("late-source", "https://tilejson.test/x.json")
        var styleState: MapStyleState? = null
        var mapState: MapState? = null
        setBrowserMapContent {
          val logicalMap = rememberMapState(initialBaseStyle = BaseStyle.Empty)
          mapState = logicalMap
          styleState = logicalMap.style
          val composition = remember {
            StyleComposition {
              if (showLateSource.value) {
                RasterLayer(id = "late-layer", source = source, visible = true)
              }
            }
          }
          MaplibreMap(
            state = logicalMap,
            styleComposition = composition,
            modifier = Modifier,
          )
        }
        waitUntilMap("the empty map to finish loading") {
          mapState?.style?.loadState == StyleLoadState.Ready
        }

        showLateSource.value = true

        waitUntilMap("the late source's initial snapshot") { styleState?.sources?.size == 1 }
        assertEquals(listOf(""), styleState?.sources?.values?.map { it.attributionHtml })
        val initialSource = styleState?.sources?.values?.single()

        waitUntilMap("MapLibre to request the TileJSON") { tileJson.isRequested() }
        tileJson.resolve()
        waitUntilMap("the late source's attribution") {
          styleState?.sources?.values?.map { it.attributionHtml } == listOf("fetched attribution")
        }
        assertNotSame(initialSource, styleState?.sources?.values?.single())
        assertEquals(
          StyleLoadState.Ready,
          mapState?.style?.loadState,
          "source metadata must not start another map load",
        )
      } finally {
        tileJson.restore()
      }
    }

  @Test
  fun switching_styles_keeps_the_sources_visible(): Promise<*> = runBrowserMapTest {
    val current = mutableStateOf(styleWith("first", "first-source"))
    var styleState: MapStyleState? = null
    var identity: StyleIdentity? = null
    var mapState: MapState? = null
    setBrowserMapContent {
      val logicalMap = rememberMapState(initialBaseStyle = current.value)
      mapState = logicalMap
      styleState = logicalMap.style
      SideEffect { logicalMap.style.baseStyle = current.value }
      val composition = remember {
        StyleComposition { identity = LocalStyleNode.current.style.identity }
      }
      MaplibreMap(
        state = logicalMap,
        styleComposition = composition,
        modifier = Modifier,
      )
    }
    waitUntilMap("the first style to load") {
      mapState?.style?.loadState == StyleLoadState.Ready &&
        styleState?.sources?.isNotEmpty() == true
    }
    val firstIdentity = identity
    assertEquals(
      listOf("first attribution"),
      styleState?.sources?.values?.map { it.attributionHtml },
    )

    val observed = mutableListOf<List<String>>()
    current.value = styleWith("second", "second-source")
    waitUntilMap("the second style's sources to be reported") {
      styleState?.sources?.values?.map { it.attributionHtml }?.let { observed += it }
      mapState?.style?.loadState == StyleLoadState.Ready &&
        styleState?.sources?.keys == setOf("second-source")
    }

    assertEquals(
      listOf("second attribution"),
      styleState?.sources?.values?.map { it.attributionHtml },
    )
    assertNotSame(firstIdentity, identity)
    assertTrue(
      observed.none { attributions -> attributions.any { it.isEmpty() } },
      "No source should ever report an empty attribution across a style switch. Observed: $observed",
    )
  }

  private companion object {
    val STYLE_A =
      BaseStyle.Json(
        """{"version":8,"name":"a","sources":{},"layers":[{"id":"a","type":"background"}]}"""
      )
    val STYLE_B =
      BaseStyle.Json(
        """{"version":8,"name":"b","sources":{},"layers":[{"id":"b","type":"background"}]}"""
      )
    val INVALID_STYLE = BaseStyle.Json("""{"version":7,"sources":{},"layers":[]}""")
    const val TILE_JSON =
      """{"tilejson":"2.2.0","tiles":["https://example.invalid/{z}/{x}/{y}.pbf"],""" +
        """"attribution":"fetched attribution"}"""
  }
}
