package org.maplibre.compose.map

import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import java.awt.Toolkit
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class DesktopViewConfigurationTapTimeoutTest {
  @Test
  fun compose_desktop_double_tap_timeout_versus_awt_multi_click_interval() = runComposeUiTest {
    var composeTimeout = 0L
    var composeMinTime = 0L
    setContent {
      val configuration = LocalViewConfiguration.current
      composeTimeout = configuration.doubleTapTimeoutMillis
      composeMinTime = configuration.doubleTapMinTimeMillis
    }
    waitForIdle()

    val awtInterval = Toolkit.getDefaultToolkit().getDesktopProperty("awt.multiClickInterval")
    val awtMillis = (awtInterval as? Number)?.toLong()
    println(
      "Compose Desktop doubleTapTimeoutMillis=$composeTimeout " +
        "doubleTapMinTimeMillis=$composeMinTime " +
        "awt.multiClickInterval=$awtMillis"
    )
    assertTrue(composeTimeout > 0, "Compose Desktop should expose a double-tap timeout")
    assertTrue(composeMinTime > 0, "Compose Desktop should expose a double-tap min time")
  }
}
