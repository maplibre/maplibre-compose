package org.maplibre.compose.overlay

import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import org.maplibre.compose.camera.Viewport
import org.maplibre.compose.map.MapState
import org.maplibre.compose.util.VisibleRegion
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.Position

@OptIn(ExperimentalTestApi::class)
class ScaleBarTest {

  @Test
  fun a_state_with_no_viewport_renders_nothing() {
    val state = MapState()
    runComposeUiTest {
      setContent { ScaleBar(state = state, modifier = Modifier.testTag("scale-bar")) }
      waitForIdle()
      onNodeWithTag("scale-bar").assertDoesNotExist()
    }
    state.close()
  }

  @Test
  fun a_state_with_a_viewport_renders_the_bar() {
    val state = MapState()
    state.viewportState =
      Viewport(
        size = DpSize(300.dp, 300.dp),
        visibleBoundingBox =
          BoundingBox(southwest = Position(-1.0, -1.0), northeast = Position(1.0, 1.0)),
        visibleRegion =
          VisibleRegion(
            farLeft = Position(-1.0, 1.0),
            farRight = Position(1.0, 1.0),
            nearLeft = Position(-1.0, -1.0),
            nearRight = Position(1.0, -1.0),
          ),
        metersPerDpAtTarget = 10.0,
      )
    runComposeUiTest {
      setContent { ScaleBar(state = state, modifier = Modifier.testTag("scale-bar")) }
      waitForIdle()
      onNodeWithTag("scale-bar").assertExists()
    }
    state.close()
  }
}
