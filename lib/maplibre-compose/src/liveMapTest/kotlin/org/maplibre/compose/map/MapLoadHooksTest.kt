package org.maplibre.compose.map

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlin.test.Test
import kotlin.test.assertEquals
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.RecordingStyleBinding

@OptIn(ExperimentalTestApi::class)
class MapLoadHooksTest {

  @Test
  fun a_recomposed_load_callback_receives_the_next_ready_state() = runComposeUiTest {
    val state =
      MapState(
        cameraPosition = CameraPosition(),
        density = Density(1f),
        layoutDirection = LayoutDirection.Ltr,
        logger = null,
      )
    var useSecond by mutableStateOf(false)
    var firstCalls = 0
    var secondCalls = 0
    setContent {
      ObserveMapLoadState(
        state = state,
        onMapLoadFinished = if (useSecond) ({ secondCalls++ }) else ({ firstCalls++ }),
        onMapLoadFailed = {},
      )
    }

    val adapter = FakeMapAdapter()
    runOnUiThread {
      state.baseStyle = STYLE
      state.attachSession(adapter)
      state.callbacks.onStyleChanged(adapter, RecordingStyleBinding())
      state.callbacks.onMapFinishedLoading(adapter)
    }
    waitForIdle()
    assertEquals(1, firstCalls)
    assertEquals(0, secondCalls)

    useSecond = true
    waitForIdle()

    runOnUiThread {
      state.baseStyle = OTHER
      state.callbacks.onStyleChanged(adapter, RecordingStyleBinding())
      state.callbacks.onMapFinishedLoading(adapter)
    }
    waitForIdle()
    assertEquals(1, firstCalls, "the first composition's callback must not receive later loads")
    assertEquals(1, secondCalls, "the recomposed callback must receive the next Ready")

    state.close()
  }

  private companion object {
    val STYLE = BaseStyle.Json("""{"version":8,"name":"a","sources":{},"layers":[]}""")
    val OTHER = BaseStyle.Json("""{"version":8,"name":"b","sources":{},"layers":[]}""")
  }
}
