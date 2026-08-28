package org.maplibre.compose.map

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.gljs.runBrowserMapTest
import org.maplibre.compose.gljs.setBrowserMapContent
import org.maplibre.compose.gljs.waitUntilMap
import org.maplibre.compose.layers.FillLayer
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.GeoJsonOptions
import org.maplibre.compose.sources.GeoJsonSource
import org.maplibre.compose.sources.Source
import org.maplibre.compose.style.BaseStyle
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.Geometry

/** The observable style collections on the browser map. */
@OptIn(ExperimentalTestApi::class)
class BrowserStyleCollectionsTest {

  private val baseStyle =
    BaseStyle.Json(
      """{"version":8,
         "sources":{"base-src":{"type":"geojson",
           "data":{"type":"FeatureCollection","features":[]}}},
         "layers":[{"id":"base-bg","type":"background",
           "paint":{"background-color":"#123456"}}]}"""
    )

  private val secondStyle =
    BaseStyle.Json(
      """{"version":8,"sources":{},
         "layers":[{"id":"second-bg","type":"background",
           "paint":{"background-color":"#654321"}}]}"""
    )

  @Test
  fun collections_split_owned_ids_and_repopulate_after_a_base_style_swap(): Promise<*> =
    runBrowserMapTest {
      val errors = mutableListOf<String>()
      val contentColor = mutableStateOf(Color.Red)
      lateinit var state: MapState
      lateinit var contentSource: Source

      setBrowserMapContent {
        state =
          rememberMapState(baseStyle = baseStyle) {
            val source = remember {
              GeoJsonSource(
                id = "content-src",
                data = GeoJsonData.Features(FeatureCollection<Geometry, JsonObject?>()),
                options = GeoJsonOptions(),
              )
            }
            contentSource = source
            FillLayer(id = "content-fill", source = source, color = const(contentColor.value))
          }
        MaplibreMap(state = state, modifier = Modifier, onMapLoadFailed = { errors += "$it" })
      }

      waitUntilMap("the base and content layers to be live") {
        "base-bg" in state.layers.ids && state.layers["content-fill"] != null
      }
      assertTrue(errors.isEmpty(), "the map reported errors: $errors")

      // (a) An imperative toggle on a base layer takes effect on the live style.
      val baseLayer = assertNotNull(state.layers["base-bg"])
      assertTrue(baseLayer.visible)
      baseLayer.visible = false
      waitUntilMap("the visibility write to land") { state.layers["base-bg"]?.visible == false }

      // (a) The toggle survives a content recomposition, because the sync never touches the id.
      val colorBefore = assertNotNull(state.layers["content-fill"]).property("fill-color")
      contentColor.value = Color.Blue
      waitUntilMap("the recomposed paint value to land") {
        state.layers["content-fill"]?.property("fill-color") != colorBefore
      }
      assertFalse(assertNotNull(state.layers["base-bg"]).visible)

      // (b) A composition-owned layer refuses imperative writes.
      val error =
        assertFailsWith<IllegalStateException> {
          assertNotNull(state.layers["content-fill"]).visible = false
        }
      assertTrue(
        "style composition" in error.message.orEmpty(),
        "the message names the owner: ${error.message}",
      )

      // (c) The composition's source comes back as the live instance it owns; base sources come
      // back as reconstructed descriptors.
      waitUntilMap("the content source to be live") { state.sources["content-src"] != null }
      assertSame(contentSource, state.sources["content-src"])
      assertNotNull(state.sources["base-src"])
      assertTrue("base-src" in state.sources.ids)

      // (d) A base-style swap repopulates the collections with the new style's ids.
      state.baseStyle = secondStyle
      waitUntilMap("the second style's layers to replace the first's") {
        "second-bg" in state.layers.ids && "base-bg" !in state.layers.ids
      }
      assertNotNull(state.layers["second-bg"])
      assertTrue(errors.isEmpty(), "the map reported errors: $errors")
    }
}
