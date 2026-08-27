@file:OptIn(DelicateMapApi::class)

package org.maplibre.compose.map

import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.maplibre.compose.gljs.runBrowserMapTest
import org.maplibre.compose.gljs.setBrowserMapContent
import org.maplibre.compose.gljs.waitUntilMap
import org.maplibre.compose.style.BaseStyle

/** [withPlatformMap] against the browser map, which lives only while a session is composed. */
@OptIn(ExperimentalTestApi::class)
class BrowserPlatformMapTest {

  private val style =
    BaseStyle.Json(
      """{"version":8,"sources":{},
         "layers":[{"id":"bg","type":"background","paint":{"background-color":"#123456"}}]}"""
    )

  @Test
  fun the_block_receives_the_live_map_while_composed(): Promise<*> = runBrowserMapTest {
    var loads = 0
    val errors = mutableListOf<String>()
    lateinit var state: MapState

    setBrowserMapContent {
      state = rememberMapState(baseStyle = style)
      MaplibreMap(
        state = state,
        modifier = Modifier,
        onMapLoadFailed = { errors += "mapLoadFailed: $it" },
        onMapLoadFinished = { loads++ },
      )
    }

    waitUntilMap("the map to load") { loads >= 1 }
    val layerIds = state.withPlatformMap { it.getLayersOrder().toList() }
    assertTrue("bg" in layerIds, "the live map must report the loaded layer, got: $layerIds")
    assertTrue(errors.isEmpty(), "the map reported errors: $errors")
  }

  @Test
  fun a_detached_state_throws(): Promise<*> = runBrowserMapTest {
    val state = MapState()
    try {
      val failure = runCatching { state.withPlatformMap {} }.exceptionOrNull()
      val message = assertNotNull(assertIs<IllegalStateException>(failure).message)
      assertTrue(
        "detached" in message,
        "the message must state the no-live-map-while-detached rule, got: $message",
      )
    } finally {
      state.close()
    }
  }
}
