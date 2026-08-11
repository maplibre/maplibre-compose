package org.maplibre.compose.overlay

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class MaplibreLogoTest {
  // The logo ships as an Android vector XML that every non-Android target parses with Compose
  // Multiplatform's own parser, so composing it is the only check that the artwork survives.
  @Test
  fun logoComposes() = runComposeUiTest {
    setContent { MaplibreLogo(contentDescription = "MapLibre") }
    waitForIdle()
    onNodeWithContentDescription("MapLibre").assertExists()
  }
}
