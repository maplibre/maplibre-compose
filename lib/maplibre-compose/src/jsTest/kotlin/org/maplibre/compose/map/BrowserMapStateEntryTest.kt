package org.maplibre.compose.map

import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertTrue
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.gljs.runBrowserMapTest
import org.maplibre.compose.gljs.setBrowserMapContent
import org.maplibre.compose.gljs.waitUntilMap
import org.maplibre.compose.layers.BackgroundLayer
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.LocalStyleNode
import org.maplibre.compose.style.StyleNode

/**
 * The hoisted entry point on the browser map: [rememberMapState] plus the [MaplibreMap] overload.
 */
@OptIn(ExperimentalTestApi::class)
class BrowserMapStateEntryTest {

  private val style =
    BaseStyle.Json(
      """{"version":8,"sources":{},
         "layers":[{"id":"bg","type":"background","paint":{"background-color":"#123456"}}]}"""
    )

  @Test
  fun style_content_on_a_remembered_state_reaches_the_engine(): Promise<*> = runBrowserMapTest {
    var node: StyleNode? = null
    var loads = 0
    val errors = mutableListOf<String>()

    setBrowserMapContent {
      val state =
        rememberMapState(baseStyle = style) {
          node = LocalStyleNode.current
          BackgroundLayer(id = "state-entry-bg", color = const(Color.Red))
        }
      MaplibreMap(
        state = state,
        modifier = Modifier,
        onMapLoadFailed = { errors += "mapLoadFailed: $it" },
        onMapLoadFinished = { loads++ },
      )
    }

    waitUntilMap("the map to load with the composed layer") {
      loads >= 1 && node?.binding?.getLayer("state-entry-bg") != null
    }
    assertTrue(errors.isEmpty(), "the map reported errors: $errors")
  }
}
