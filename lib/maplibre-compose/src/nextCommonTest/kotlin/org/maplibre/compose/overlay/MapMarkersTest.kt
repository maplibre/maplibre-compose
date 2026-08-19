package org.maplibre.compose.overlay

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.CameraState
import org.maplibre.spatialk.geojson.Position

@OptIn(ExperimentalTestApi::class)
class MapMarkersTest {
  @Test
  fun markers_compose_before_the_map_attaches() = runComposeUiTest {
    setContent {
      MapMarkers(CameraState(CameraPosition())) {
        Box(Modifier.size(8.dp).placedAt(Position(0.0, 0.0)))
      }
    }
    waitForIdle()
  }
}
