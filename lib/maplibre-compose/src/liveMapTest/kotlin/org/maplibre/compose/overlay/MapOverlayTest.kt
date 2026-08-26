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
import org.maplibre.compose.map.MapState
import org.maplibre.spatialk.geojson.Position

@OptIn(ExperimentalTestApi::class)
class MapOverlayTest {
  @Test
  fun overlay_composes_before_the_map_attaches() {
    // Closed after the test: a leaked state's style host would observe snapshot writes globally.
    val mapState = MapState()
    try {
      runComposeUiTest {
        setContent {
          MapOverlayHost(
            overlay =
              MapOverlay {
                Box(Modifier.size(8.dp).placedAt(Position(0.0, 0.0)))
                Box(Modifier.size(8.dp).placedTowards(Position(90.0, 0.0)))
                Box(Modifier.size(8.dp).align(Alignment.TopStart))
              },
            state = mapState,
            contentWindowInsets = WindowInsets(0),
          )
        }
        waitForIdle()
      }
    } finally {
      mapState.close()
    }
  }

  @Test
  fun removing_a_placed_towards_child_resets_its_state() {
    val mapState = MapState()
    try {
      runComposeUiTest {
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
            state = mapState,
            contentWindowInsets = WindowInsets(0),
          )
        }
        waitForIdle()
        show = false
        waitForIdle()
        assertFalse(state.isPlaced)
      }
    } finally {
      mapState.close()
    }
  }
}
