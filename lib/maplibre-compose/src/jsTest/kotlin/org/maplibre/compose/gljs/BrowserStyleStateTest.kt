package org.maplibre.compose.gljs

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertTrue
import kotlinx.browser.window
import org.maplibre.compose.layers.RasterLayer
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.sources.RasterSource
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.LocalStyleNode
import org.maplibre.compose.style.StyleNode
import org.maplibre.compose.style.StyleState
import org.maplibre.compose.style.rememberStyleState

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
  fun a_source_reports_the_attribution_its_tilejson_carries(): Promise<*> = runBrowserMapTest {
    val restoreFetch = installSlowTileJson()
    try {
      var state: StyleState? = null
      var loaded = false
      setBrowserMapContent {
        val styleState = rememberStyleState()
        state = styleState
        MaplibreMap(
          modifier = Modifier,
          baseStyle = tileJsonStyle,
          styleState = styleState,
          onMapLoadFinished = { loaded = true },
        )
      }
      waitUntilMap("the map to report that it finished loading") { loaded }

      assertEquals(
        listOf("fetched attribution"),
        state?.sources?.values?.map { it.attributionHtml },
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
      var current by mutableStateOf(styleWith("first", "first-source"))
      var state: StyleState? = null
      var loads = 0
      setBrowserMapContent {
        val styleState = rememberStyleState()
        state = styleState
        MaplibreMap(
          modifier = Modifier,
          baseStyle = current,
          styleState = styleState,
          onMapLoadFinished = { loads += 1 },
        )
      }
      waitUntilMap("the first style to load") { loads >= 1 }

      current = tileJsonStyle
      waitUntilMap("the switched style's attribution to be reported") {
        state?.sources?.values?.map { it.attributionHtml } == listOf("fetched attribution")
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
        var node: StyleNode? = null
        var state: StyleState? = null
        var loads = 0
        setBrowserMapContent {
          val styleState = rememberStyleState()
          state = styleState
          MaplibreMap(
            modifier = Modifier,
            baseStyle = BaseStyle.Empty,
            styleState = styleState,
            onMapLoadFinished = { loads += 1 },
          ) {
            node = LocalStyleNode.current
          }
        }
        waitUntilMap("the empty map to finish loading") { loads == 1 && node != null }

        val source = RasterSource("late-source", "https://tilejson.test/x.json")
        checkNotNull(node).let { liveNode ->
          liveNode.sourceManager.addReference(source)
          liveNode.style.addLayer(RasterLayer(id = "late-layer", source = source))
        }

        waitUntilMap("the late source's initial snapshot") { state?.sources?.size == 1 }
        assertEquals(listOf(""), state?.sources?.values?.map { it.attributionHtml })
        val initialSource = state?.sources?.values?.single()

        waitUntilMap("MapLibre to request the TileJSON") { tileJson.isRequested() }
        tileJson.resolve()
        waitUntilMap("the late source's attribution") {
          state?.sources?.values?.map { it.attributionHtml } == listOf("fetched attribution")
        }
        assertNotSame(initialSource, state?.sources?.values?.single())
        assertEquals(1, loads, "source metadata must not report another map load")
      } finally {
        tileJson.restore()
      }
    }

  @Test
  fun switching_styles_keeps_the_sources_visible(): Promise<*> = runBrowserMapTest {
    var current by mutableStateOf(styleWith("first", "first-source"))
    var state: StyleState? = null
    var loads = 0
    setBrowserMapContent {
      val styleState = rememberStyleState()
      state = styleState
      MaplibreMap(
        modifier = Modifier,
        baseStyle = current,
        styleState = styleState,
        onMapLoadFinished = { loads += 1 },
      )
    }
    waitUntilMap("the first style to load") { loads >= 1 && state?.sources?.isNotEmpty() == true }
    assertEquals(listOf("first attribution"), state?.sources?.values?.map { it.attributionHtml })

    // Sampled per frame, not just at the end: a window where the attribution is empty would flicker
    // the attribution UI.
    val observed = mutableListOf<List<String>>()
    current = styleWith("second", "second-source")
    waitUntilMap("the second style's sources to be reported") {
      state?.sources?.values?.map { it.attributionHtml }?.let { observed += it }
      loads >= 2 && state?.sources?.keys == setOf("second-source")
    }

    assertEquals(listOf("second attribution"), state?.sources?.values?.map { it.attributionHtml })
    assertTrue(
      observed.none { attributions -> attributions.any { it.isEmpty() } },
      "No source should ever report an empty attribution across a style switch. Observed: $observed",
    )
  }

  private companion object {
    const val TILE_JSON =
      """{"tilejson":"2.2.0","tiles":["https://example.invalid/{z}/{x}/{y}.pbf"],""" +
        """"attribution":"fetched attribution"}"""
  }
}
