@file:OptIn(ExperimentalAtomicApi::class)

package org.maplibre.compose.map

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import co.touchlab.kermit.Logger
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.layers.FillLayer
import org.maplibre.compose.mlnffi.FfiTestPlatform
import org.maplibre.compose.mlnffi.MlnFfiRuntimeOptions
import org.maplibre.compose.mlnffi.runFfiComposeUiTest
import org.maplibre.compose.mlnffi.setFfiTestMapContent
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.GeoJsonOptions
import org.maplibre.compose.sources.GeoJsonSource
import org.maplibre.compose.sources.Source
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.testing.RecordingList
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.Geometry

/** The observable style collections on a real map: [MapState.layers] and [MapState.sources]. */
@OptIn(ExperimentalTestApi::class)
class MlnFfiStyleCollectionsTest {

  private val cacheFile = FfiTestPlatform.createCacheFile()

  private val runtimeOptions =
    MlnFfiRuntimeOptions(cacheFile = cacheFile, maximumCacheSizeBytes = null)

  @AfterTest
  fun cleanUp() {
    FfiTestPlatform.deleteCacheFile(cacheFile)
  }

  @Test
  fun collections_split_map_owned_and_composition_owned_ids() = runFfiComposeUiTest {
    val errors = RecordingList<String>()
    val frames = AtomicInt(0)
    val contentColor = mutableStateOf(Color.Red)
    lateinit var state: MapState
    lateinit var contentSource: Source

    setFfiTestMapContent(runtimeOptions) {
      state =
        rememberMapState(baseStyle = BASE_STYLE) {
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
      MaplibreMap(
        state = state,
        modifier = Modifier,
        logger = Logger.withTag("style-collections-test"),
        onMapLoadFailed = { errors += "mapLoadFailed: $it" },
        onFrame = { frames.incrementAndFetch() },
      )
    }

    waitUntil(timeoutMillis = RENDER_TIMEOUT_MILLIS) { frames.load() > 0 || errors.isNotEmpty() }
    assertTrue(errors.isEmpty(), "The composition reported errors: $errors")
    waitUntil(timeoutMillis = RENDER_TIMEOUT_MILLIS) {
      "base-bg" in state.layers.ids && state.layers["content-fill"] != null
    }

    // (a) An imperative toggle on a base layer takes effect on the live style.
    val baseLayer = assertNotNull(state.layers["base-bg"])
    assertTrue(baseLayer.visible)
    baseLayer.visible = false
    waitUntil(timeoutMillis = RENDER_TIMEOUT_MILLIS) {
      state.layers["base-bg"]?.visible == false
    }

    // (a) The toggle survives a content recomposition, because the sync never touches the id.
    val contentLayer = assertNotNull(state.layers["content-fill"])
    val colorBefore = contentLayer.property("fill-color")
    contentColor.value = Color.Blue
    waitForIdle()
    waitUntil(timeoutMillis = RENDER_TIMEOUT_MILLIS) {
      state.layers["content-fill"]?.property("fill-color") != colorBefore
    }
    assertFalse(assertNotNull(state.layers["base-bg"]).visible)

    // (b) A composition-owned layer refuses imperative writes.
    val error =
      assertFailsWith<IllegalStateException> {
        assertNotNull(state.layers["content-fill"]).visible = false
      }
    assertTrue(
      "style content composition" in error.message.orEmpty(),
      "the message names the owner: ${error.message}",
    )

    // (c) The composition's source comes back as the live instance it owns; base sources come
    // back as reconstructed descriptors.
    waitUntil(timeoutMillis = RENDER_TIMEOUT_MILLIS) { state.sources["content-src"] != null }
    assertSame(contentSource, state.sources["content-src"])
    assertNotNull(state.sources["base-src"])
    assertTrue("base-src" in state.sources.ids)
  }

  @Test
  fun collections_repopulate_after_a_base_style_swap() = runFfiComposeUiTest {
    val errors = RecordingList<String>()
    val frames = AtomicInt(0)
    lateinit var state: MapState

    setFfiTestMapContent(runtimeOptions) {
      state = rememberMapState(baseStyle = BASE_STYLE)
      MaplibreMap(
        state = state,
        modifier = Modifier,
        logger = Logger.withTag("style-collections-swap-test"),
        onMapLoadFailed = { errors += "mapLoadFailed: $it" },
        onFrame = { frames.incrementAndFetch() },
      )
    }

    waitUntil(timeoutMillis = RENDER_TIMEOUT_MILLIS) { frames.load() > 0 || errors.isNotEmpty() }
    assertTrue(errors.isEmpty(), "The composition reported errors: $errors")
    waitUntil(timeoutMillis = RENDER_TIMEOUT_MILLIS) { "base-bg" in state.layers.ids }

    state.baseStyle = SECOND_STYLE
    waitUntil(timeoutMillis = RENDER_TIMEOUT_MILLIS) {
      "second-bg" in state.layers.ids && "base-bg" !in state.layers.ids
    }
    assertNotNull(state.layers["second-bg"])
  }

  private companion object {
    const val RENDER_TIMEOUT_MILLIS = 30_000L

    val BASE_STYLE =
      BaseStyle.Json(
        """{"version":8,
           "sources":{"base-src":{"type":"geojson",
             "data":{"type":"FeatureCollection","features":[]}}},
           "layers":[{"id":"base-bg","type":"background",
             "paint":{"background-color":"#123456"}}]}"""
      )

    val SECOND_STYLE =
      BaseStyle.Json(
        """{"version":8,"sources":{},
           "layers":[{"id":"second-bg","type":"background",
             "paint":{"background-color":"#654321"}}]}"""
      )
  }
}
