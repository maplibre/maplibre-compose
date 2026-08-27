package org.maplibre.compose.overlay

import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class ScaleBarTest {

  /** A scale of 0 stands for a map with no viewport, so the numeric overload renders nothing. */
  @Test
  fun a_zero_scale_renders_nothing() {
    runComposeUiTest {
      setContent { ScaleBar(metersPerDp = 0.0, modifier = Modifier.testTag("scale-bar")) }
      waitForIdle()
      onNodeWithTag("scale-bar").assertDoesNotExist()
    }
  }

  @Test
  fun a_positive_scale_renders_the_bar() {
    runComposeUiTest {
      setContent { ScaleBar(metersPerDp = 10.0, modifier = Modifier.testTag("scale-bar")) }
      waitForIdle()
      onNodeWithTag("scale-bar").assertExists()
    }
  }
}
