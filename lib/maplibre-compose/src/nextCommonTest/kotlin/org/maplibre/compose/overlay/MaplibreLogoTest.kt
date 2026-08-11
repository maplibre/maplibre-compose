package org.maplibre.compose.overlay

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class MaplibreLogoTest {
  // The logo is an Android vector XML, which every target but Android parses with Compose
  // Multiplatform's own reader. Composing it is the only check that the artwork survives that.
  @Test
  fun logoComposes() = runComposeUiTest {
    setContent { MaplibreLogo(contentDescription = "MapLibre") }
    waitForIdle()
    onNodeWithContentDescription("MapLibre").assertExists()
  }
}
