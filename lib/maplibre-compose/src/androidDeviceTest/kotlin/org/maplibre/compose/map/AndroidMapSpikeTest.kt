package org.maplibre.compose.map

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runAndroidComposeUiTest
import kotlin.test.Test
import org.maplibre.compose.style.BaseStyle

@OptIn(ExperimentalTestApi::class)
class AndroidMapSpikeTest {
  @Test
  fun mapLoadsEmptyStyle() =
    runAndroidComposeUiTest<ComponentActivity> {
      var loaded = false
      setContent { MaplibreMap(baseStyle = BaseStyle.Empty, onMapLoadFinished = { loaded = true }) }
      waitUntil(timeoutMillis = 10_000) { loaded }
      assert(loaded)
    }
}
