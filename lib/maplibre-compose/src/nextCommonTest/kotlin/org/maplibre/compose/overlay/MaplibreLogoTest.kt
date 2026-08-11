package org.maplibre.compose.overlay

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class MaplibreLogoTest {
  // The logo ships as an Android vector XML that desktop, the browser, and iOS all parse with the
  // same Compose Multiplatform parser, rather than with the Android framework's. Composing it is
  // the only check that the artwork survives that parser.
  @Test
  fun logoComposes() = runComposeUiTest {
    setContent { MaplibreLogo(contentDescription = "MapLibre") }
    waitForIdle()
    onNodeWithContentDescription("MapLibre").assertExists()
  }
}
