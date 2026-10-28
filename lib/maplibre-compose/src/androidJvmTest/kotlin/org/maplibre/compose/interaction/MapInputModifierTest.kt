@file:OptIn(
  ExperimentalAtomicApi::class,
  ExperimentalMaplibreComposeApi::class,
  ExperimentalTestApi::class,
)

package org.maplibre.compose.interaction

import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.Interaction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.moveBy
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.requestFocus
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.launch
import org.maplibre.compose.interaction.internal.RecognitionMapTag
import org.maplibre.compose.interaction.internal.composeGestureFixture
import org.maplibre.compose.interaction.internal.mapNode
import org.maplibre.compose.map.UnconfinedMain
import org.maplibre.compose.map.mapRuntimeForTest
import org.maplibre.compose.mlnffi.runPlainComposeUiTest
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.util.ExperimentalMaplibreComposeApi
import org.maplibre.spatialk.geojson.Position

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
  fun starting_from_no_bindings_ignores_a_drag() = runPlainComposeUiTest {
    val target = fixture.target
    setContent {
      Box(
        Modifier.fillMaxSize()
          .testTag(RecognitionMapTag)
          .mapInput(fixture.state, InteractionBindings.None),
      )
    }
    waitForIdle()
    mapNode().performTouchInput {
      down(center)
      moveBy(Offset(200f, 0f))
      up()
    }
    waitForIdle()
    assertTrue(target.moveCalls.isEmpty(), "a drag panned the map without bindings")
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

  @Test
  fun a_shift_drag_draws_the_selection_over_the_presented_map() = runPlainComposeUiTest {
    val target = fixture.target
    target.project = { Position(it.x.value.toDouble(), -it.y.value.toDouble()) }
    setContent { Box(Modifier.fillMaxSize().testTag(RecognitionMapTag).mapInput(fixture.state)) }
    waitForIdle()
    val map = mapNode()
    val before = map.captureToImage().toPixelMap()[50, 50]
    map.performKeyInput { keyDown(Key.ShiftLeft) }
    map.performMouseInput {
      moveTo(Offset(20f, 20f))
      press()
      moveTo(Offset(120f, 80f))
    }
    waitForIdle()
    assertTrue(before != map.captureToImage().toPixelMap()[50, 50], "no selection was drawn")
    map.performMouseInput { release() }
    map.performKeyInput { keyUp(Key.ShiftLeft) }
    waitForIdle()
    assertEquals(before, map.captureToImage().toPixelMap()[50, 50])
    assertEquals(1, target.fitCalls.size)
  }

  @Test
  fun focus_shows_the_current_indication() = runPlainComposeUiTest {
    val indication = RecordingIndication()
    setContent {
      CompositionLocalProvider(LocalIndication provides indication) {
        Box(Modifier.fillMaxSize().testTag(RecognitionMapTag).mapInput(fixture.state))
      }
    }
    waitForIdle()
    mapNode().requestFocus()
    waitForIdle()
    assertTrue(indication.interactions.any { it is FocusInteraction.Focus })
  }
}

private class RecordingIndication : IndicationNodeFactory {
  val interactions = mutableListOf<Interaction>()

  override fun create(interactionSource: InteractionSource): DelegatableNode =
    object : Modifier.Node() {
      override fun onAttach() {
        coroutineScope.launch { interactionSource.interactions.collect { interactions += it } }
      }
    }

  override fun equals(other: Any?): Boolean = other === this

  override fun hashCode(): Int = System.identityHashCode(this)
}
