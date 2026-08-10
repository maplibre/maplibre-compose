package org.maplibre.compose.gljs

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import kotlin.test.Test
import kotlin.test.assertTrue
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.style.BaseStyle

@OptIn(ExperimentalTestApi::class)
class BrowserMapLifecycleTest {

  private val style = BaseStyle.Json("""{"version":8,"name":"a","sources":{},"layers":[]}""")

  private val otherStyle =
    BaseStyle.Json(
      """{"version":8,"name":"b","sources":{},
         "layers":[{"id":"bg","type":"background","paint":{"background-color":"#123456"}}]}"""
    )

  @Test
  fun a_map_can_be_taken_out_of_the_composition() = runBrowserMapTest {
    var visible by mutableStateOf(true)
    var loaded = false
    setBrowserMapContent {
      if (visible) {
        MaplibreMap(modifier = Modifier, baseStyle = style, onMapLoadFinished = { loaded = true })
      }
    }
    waitUntilMap("the map to load") { loaded }

    visible = false
    waitForIdle()
    repeat(5) {
      yieldToBrowser()
      waitForIdle()
    }
  }

  @Test
  fun a_map_can_be_taken_out_and_put_back() = runBrowserMapTest {
    var visible by mutableStateOf(true)
    var loads = 0
    setBrowserMapContent {
      if (visible) {
        MaplibreMap(modifier = Modifier, baseStyle = style, onMapLoadFinished = { loads += 1 })
      }
    }
    waitUntilMap("the first map to load") { loads >= 1 }

    visible = false
    repeat(5) {
      yieldToBrowser()
      waitForIdle()
    }
    visible = true
    waitUntilMap("the replacement map to load") { loads >= 2 }
  }

  @Test
  fun switching_the_base_style_reports_loading_finished_again() = runBrowserMapTest {
    var current by mutableStateOf(style)
    var loads = 0
    setBrowserMapContent {
      MaplibreMap(modifier = Modifier, baseStyle = current, onMapLoadFinished = { loads += 1 })
    }
    waitUntilMap("the first style to load") { loads >= 1 }

    current = otherStyle
    waitUntilMap("the second style to report that it finished loading") { loads >= 2 }
    assertTrue(loads >= 2, "every style load should report finishing, not only the first")
  }
}
