@file:OptIn(
  ExperimentalAtomicApi::class,
  ExperimentalMaplibreComposeApi::class,
  ExperimentalTestApi::class,
)

package org.maplibre.compose.interaction

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.moveBy
import androidx.compose.ui.test.performTouchInput
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue
import org.maplibre.compose.interaction.internal.RecognitionMapTag
import org.maplibre.compose.interaction.internal.composeGestureFixture
import org.maplibre.compose.interaction.internal.mapNode
import org.maplibre.compose.map.UnconfinedMain
import org.maplibre.compose.map.mapRuntimeForTest
import org.maplibre.compose.mlnffi.runPlainComposeUiTest
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.util.ExperimentalMaplibreComposeApi

class MapInputModifierTest {
  private val fixture = composeGestureFixture()

  @AfterTest fun closeMap() = fixture.close()

  @Test
  fun a_presented_map_pans_when_the_modifier_recognizes_a_drag() = runPlainComposeUiTest {
    val target = fixture.target
    setContent { Box(Modifier.fillMaxSize().testTag(RecognitionMapTag).mapInput(fixture.state)) }
    waitForIdle()
    mapNode().performTouchInput {
      down(center)
      moveBy(Offset(200f, 0f))
      up()
    }
    waitForIdle()
    assertTrue(target.moveCalls.isNotEmpty(), "the recognized drag did not pan the presented map")
  }

  @Test
  fun a_disabled_drag_binding_does_not_pan() = runPlainComposeUiTest {
    val target = fixture.target
    setContent {
      Box(
        Modifier.fillMaxSize().testTag(RecognitionMapTag).mapInput(fixture.state) {
          drag { enabled = false }
        },
      )
    }
    waitForIdle()
    mapNode().performTouchInput {
      down(center)
      moveBy(Offset(200f, 0f))
      up()
    }
    waitForIdle()
    assertTrue(target.moveCalls.isEmpty(), "a disabled drag binding still panned the map")
  }

  @Test
  fun the_modifier_does_nothing_while_no_map_is_presented() = runPlainComposeUiTest {
    val runtime = mapRuntimeForTest(mainDispatcher = UnconfinedMain)
    val state = runtime.createMapState(BaseStyle.Empty)
    try {
      setContent { Box(Modifier.fillMaxSize().testTag(RecognitionMapTag).mapInput(state)) }
      waitForIdle()
      mapNode().performTouchInput {
        down(center)
        moveBy(Offset(200f, 0f))
        up()
      }
      waitForIdle()
    } finally {
      state.close()
      runtime.close()
    }
  }
}
