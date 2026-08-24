package org.maplibre.compose.overlay

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertFalse
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.CameraState
import org.maplibre.compose.style.StyleState
import org.maplibre.spatialk.geojson.Position

@OptIn(ExperimentalTestApi::class)
class MapOverlayTest {
  @Test
  fun overlay_composes_before_the_map_attaches() = runComposeUiTest {
    setContent {
      MapOverlayHost(
        overlay =
          MapOverlay {
            Box(Modifier.size(8.dp).placedAt(Position(0.0, 0.0)))
            Box(Modifier.size(8.dp).placedTowards(Position(90.0, 0.0)))
            Box(Modifier.size(8.dp).align(Alignment.TopStart))
          },
        cameraState = CameraState(CameraPosition()),
        styleState = StyleState(),
        contentWindowInsets = WindowInsets(0),
      )
    }
    waitForIdle()
  }

  @Test
  fun removing_a_placed_towards_child_resets_its_state() = runComposeUiTest {
    val state = PlacedTowardsState().apply { isPlaced = true }
    var show by mutableStateOf(true)
    setContent {
      MapOverlayHost(
        overlay =
          MapOverlay {
            if (show) {
              Box(Modifier.size(8.dp).placedTowards(Position(90.0, 0.0), state))
            }
          },
        cameraState = CameraState(CameraPosition()),
        styleState = StyleState(),
        contentWindowInsets = WindowInsets(0),
      )
    }
    waitForIdle()
    show = false
    waitForIdle()
    assertFalse(state.isPlaced)
  }
}
