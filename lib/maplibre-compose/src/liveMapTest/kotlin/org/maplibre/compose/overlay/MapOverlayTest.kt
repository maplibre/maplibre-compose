package org.maplibre.compose.overlay

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertFalse
import org.maplibre.compose.map.mapRuntimeForTest
import org.maplibre.compose.style.BaseStyle
import org.maplibre.spatialk.geojson.Position

@OptIn(ExperimentalTestApi::class)
class MapOverlayTest {
  @Test
  fun overlay_composes_before_the_map_attaches() = runComposeUiTest {
    val mapState = mapRuntimeForTest().createMapState(baseStyle = BaseStyle.Empty)
    setContent {
      MapOverlayHost(
        overlay = {
          Box(Modifier.size(8.dp).testTag("at").placedAt(Position(0.0, 0.0)))
          Box(Modifier.size(8.dp).testTag("towards").placedTowards(Position(90.0, 0.0)))
          Box(Modifier.size(8.dp).testTag("aligned").align(Alignment.TopStart))
        },
        mapState = mapState,
        contentWindowInsets = WindowInsets(0),
      )
    }
    waitForIdle()
    onNodeWithTag("aligned").assertIsDisplayed()
    onNodeWithTag("at").assertIsNotDisplayed()
    onNodeWithTag("towards").assertIsNotDisplayed()
    mapState.close()
  }

  @Test
  fun removing_a_placed_towards_child_resets_its_state() = runComposeUiTest {
    val mapState = mapRuntimeForTest().createMapState(baseStyle = BaseStyle.Empty)
    val state = PlacedTowardsState()
    var show by mutableStateOf(true)
    setContent {
      MapOverlayHost(
        overlay = {
          if (show) {
            Box(Modifier.size(8.dp).placedTowards(Position(90.0, 0.0), state))
          }
        },
        mapState = mapState,
        contentWindowInsets = WindowInsets(0),
      )
    }
    waitForIdle()
    runOnIdle {
      state.isPlaced = true
      show = false
    }
    waitForIdle()
    assertFalse(state.isPlaced)
    mapState.close()
  }
}
