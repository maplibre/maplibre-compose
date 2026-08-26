package org.maplibre.compose.map

import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import kotlin.js.Promise
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.gljs.runBrowserMapTest
import org.maplibre.compose.gljs.setBrowserMapContent
import org.maplibre.compose.gljs.waitUntilMap
import org.maplibre.compose.layers.BackgroundLayer
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.LocalStyleNode
import org.maplibre.compose.style.StyleNode
import org.maplibre.spatialk.geojson.Position

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

  @Test
  fun a_camera_position_set_before_attach_applies_at_attach(): Promise<*> = runBrowserMapTest {
    var loads = 0
    val errors = mutableListOf<String>()
    lateinit var state: MapState

    setBrowserMapContent {
      state = rememberMapState(cameraPosition = FIRST_POSITION, baseStyle = style)
      MaplibreMap(
        state = state,
        modifier = Modifier,
        onMapLoadFailed = { errors += "mapLoadFailed: $it" },
        onMapLoadFinished = { loads++ },
      )
    }

    waitUntilMap("the map to load at the first camera position") {
      loads >= 1 && (state.attachedAdapter?.getCameraPosition()?.isNear(FIRST_POSITION) ?: false)
    }
    assertTrue(errors.isEmpty(), "the map reported errors: $errors")
  }

  private fun CameraPosition.isNear(other: CameraPosition): Boolean =
    abs(zoom - other.zoom) < 0.01 &&
      abs(target.longitude - other.target.longitude) < 0.01 &&
      abs(target.latitude - other.target.latitude) < 0.01

  private companion object {
    val FIRST_POSITION =
      CameraPosition(target = Position(longitude = 11.39085, latitude = 47.26266), zoom = 6.0)
  }
}
