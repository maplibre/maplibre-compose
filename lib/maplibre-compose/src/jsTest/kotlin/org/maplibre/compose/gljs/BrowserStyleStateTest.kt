package org.maplibre.compose.gljs

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.browser.window
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.StyleState
import org.maplibre.compose.style.rememberStyleState

/**
 * The attribution UI reads `StyleState`'s sources, so anything stale is a control that vanishes.
 */
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

  /**
   * A data URL will not do: it resolves before the style loads. This reproduces a source whose
   * attribution is not readable until well after.
   */
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

  /** The demo's shape: two styles whose attribution arrives with a TileJSON. */
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
      // Waits for the attribution itself, so a timeout here means it never arrives rather than
      // that it arrived after the assertion.
      waitUntilMap("the switched style's attribution to be reported") {
        state?.sources?.values?.map { it.attributionHtml } == listOf("fetched attribution")
      }
    } finally {
      restoreFetch()
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

    // Sampled per frame rather than at the end: the attribution UI reads this every recomposition,
    // so a window where it is empty is a control that vanishes and comes back.
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
