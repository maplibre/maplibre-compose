package org.maplibre.compose.overlay

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import org.maplibre.compose.map.mapRuntimeForTest
import org.maplibre.compose.style.BaseStyle

// The browser loads string resources asynchronously, and the UI test harness there does not wait
// for them, so these tests pass literal labels or match by role instead of by the default labels.
@OptIn(ExperimentalTestApi::class)
class ZoomButtonsTest {
  @Test
  fun zoom_buttons_compose_before_the_map_attaches_and_report_clicks() = runComposeUiTest {
    val mapState = mapRuntimeForTest().createMapState(baseStyle = BaseStyle.Empty)
    var zoomInClicks = 0
    var zoomOutClicks = 0
    setContent {
      MapOverlayHost(
        overlay = {
          ZoomButtons(
            modifier = Modifier.align(Alignment.BottomEnd),
            onZoomIn = { zoomInClicks++ },
            onZoomOut = { zoomOutClicks++ },
            contentDescriptionZoomIn = "Zoom in",
            contentDescriptionZoomOut = "Zoom out",
          )
        },
        mapState = mapState,
        contentWindowInsets = WindowInsets(0),
      )
    }
    waitForIdle()

    onNodeWithContentDescription("Zoom in").assertIsDisplayed().performClick()
    onNodeWithContentDescription("Zoom out").assertIsDisplayed().performClick()
    runOnIdle {
      assertEquals(1, zoomInClicks)
      assertEquals(1, zoomOutClicks)
    }
  }

  @Test
  fun full_overlay_draws_zoom_buttons() = runComposeUiTest {
    val mapState = mapRuntimeForTest().createMapState(baseStyle = BaseStyle.Empty)
    setContent {
      MapOverlayHost(
        overlay = { include(MapOverlay.Full) },
        mapState = mapState,
        contentWindowInsets = WindowInsets(0),
      )
    }
    waitForIdle()

    onAllNodes(isButton()).assertCountEquals(2)
  }
}

private fun isButton() = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)
