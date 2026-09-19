package org.maplibre.compose.editing

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.maplibre.compose.interaction.KeyModifier
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.Position

@OptIn(ExperimentalTestApi::class)
class FeatureEditorModifierTest {
  private class RecordingTool(
    var claim: Boolean = false,
    var consumeTap: Boolean = false,
    var consumeKey: Boolean = false,
  ) : EditorTool {
    val events = mutableListOf<EditorEvent>()

    override fun onEvent(event: EditorEvent, state: FeatureEditorState): Boolean {
      events += event
      return when (event) {
        is EditorEvent.Press -> claim
        is EditorEvent.Tap -> consumeTap
        is EditorEvent.Key -> consumeKey
        else -> false
      }
    }
  }

  /** One change as a descendant saw it in the Main pass: pressed, previously pressed, consumed. */
  private data class Seen(val pressed: Boolean, val previousPressed: Boolean, val consumed: Boolean)

  private fun project(position: Position): DpOffset =
    screenOf(position.longitude, position.latitude)

  private fun ComposeUiTest.editor(
    state: FeatureEditorState,
    enabled: () -> Boolean = { true },
    undoShortcuts: Boolean = true,
    cameraKey: () -> Any? = { null },
    visibleBounds: () -> BoundingBox? = { null },
    seen: MutableList<Seen> = mutableListOf(),
    focusRequester: FocusRequester? = null,
  ) {
    setContent {
      Box(
        Modifier.size(200.dp)
          .testTag("editor")
          .featureEditor(
            state,
            ::project,
            ::unproject,
            cameraKey,
            visibleBounds,
            enabled = enabled(),
            undoShortcuts = undoShortcuts,
          )
      ) {
        Box(
          Modifier.fillMaxSize()
            .then(focusRequester?.let { Modifier.focusRequester(it).focusable() } ?: Modifier)
            .pointerInput(Unit) {
              awaitPointerEventScope {
                while (true) {
                  val event = awaitPointerEvent(PointerEventPass.Main)
                  event.changes.forEach {
                    seen += Seen(it.pressed, it.previousPressed, it.isConsumed)
                  }
                }
              }
            }
        )
      }
    }
  }

  @Test
  fun claimedPointerIsConsumedInInitialThroughRelease() = runComposeUiTest {
    val tool = RecordingTool(claim = true)
    val state = FeatureEditorState(listOf(point("a", 10.0, -10.0)), initialTool = tool)
    val seen = mutableListOf<Seen>()
    editor(state, seen = seen)

    onNodeWithTag("editor").performTouchInput { down(Offset(100.dp.toPx(), 100.dp.toPx())) }
    runOnIdle {
      val press = assertIs<EditorEvent.Press>(tool.events.single())
      assertEquals(id("a"), assertIs<FeatureHit>(press.hit).featureId)
      assertTrue(state.gestureInProgress)
      assertEquals(listOf(Seen(true, false, true)), seen)
    }

    onNodeWithTag("editor").performTouchInput { moveBy(Offset(60.dp.toPx(), 0.dp.toPx())) }
    runOnIdle {
      val drag = assertIs<EditorEvent.Drag>(tool.events.last())
      assertEquals(drag.origin, drag.previous)
      assertEquals(Seen(true, true, true), seen.last())
    }

    onNodeWithTag("editor").performTouchInput { up() }
    runOnIdle {
      assertIs<EditorEvent.Release>(tool.events.last())
      assertFalse(state.gestureInProgress)
      assertEquals(Seen(false, true, true), seen.last())
      assertEquals(3, tool.events.size)
    }
  }

  @Test
  fun unclaimedTapPassesToDescendantsUnlessTheToolConsumesIt() = runComposeUiTest {
    val tool = RecordingTool()
    val state = FeatureEditorState(initialTool = tool)
    val seen = mutableListOf<Seen>()
    editor(state, seen = seen)

    onNodeWithTag("editor").performTouchInput { click(Offset(50.dp.toPx(), 50.dp.toPx())) }
    runOnIdle {
      val tap = assertIs<EditorEvent.Tap>(tool.events.last())
      assertEquals(1, tap.count)
      assertNull(tap.hit)
      assertEquals(listOf(Seen(true, false, false), Seen(false, true, false)), seen)
      assertFalse(state.gestureInProgress)
    }

    tool.consumeTap = true
    seen.clear()
    onNodeWithTag("editor").performTouchInput { click(Offset(150.dp.toPx(), 150.dp.toPx())) }
    runOnIdle {
      assertIs<EditorEvent.Tap>(tool.events.last())
      assertEquals(listOf(Seen(true, false, false), Seen(false, true, true)), seen)
    }
  }

  @Test
  fun mouseHoverUpdatesTheStateAndReachesTheTool() = runComposeUiTest {
    val tool = RecordingTool()
    val state = FeatureEditorState(listOf(point("a", 10.0, -10.0)), initialTool = tool)
    editor(state)

    onNodeWithTag("editor").performMouseInput { moveTo(Offset(100.dp.toPx(), 100.dp.toPx())) }
    runOnIdle {
      val hover = assertIs<EditorEvent.Hover>(tool.events.last())
      assertIs<FeatureHit>(hover.hit)
      assertEquals(hover.hit, state.hover)
    }

    onNodeWithTag("editor").performMouseInput { moveTo(Offset(10.dp.toPx(), 10.dp.toPx())) }
    runOnIdle {
      assertNull(state.hover)
      assertNull(assertIs<EditorEvent.Hover>(tool.events.last()).hit)
    }
  }

  @Test
  fun keysReachTheToolThroughTheFocusedChild() = runComposeUiTest {
    val tool = RecordingTool()
    val state = FeatureEditorState(initialTool = tool)
    val focusRequester = FocusRequester()
    editor(state, focusRequester = focusRequester)
    runOnIdle { focusRequester.requestFocus() }

    onRoot().performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.A) } }
    runOnIdle {
      val keys = tool.events.filterIsInstance<EditorEvent.Key>().filter { it.key == Key.A }
      assertEquals(listOf(KeyEventType.KeyDown, KeyEventType.KeyUp), keys.map { it.type })
      assertTrue(keys.all { it.modifierKeys == setOf(KeyModifier.Shift) })
    }
  }

  @Test
  fun undoShortcutsActOnTheStateBeforeTheTool() = runComposeUiTest {
    val tool = RecordingTool()
    val state = FeatureEditorState(initialTool = tool)
    state.add(point("a"))
    val focusRequester = FocusRequester()
    editor(state, focusRequester = focusRequester)
    runOnIdle { focusRequester.requestFocus() }

    onRoot().performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.Z) } }
    runOnIdle {
      assertTrue(state.features.isEmpty())
      assertTrue(
        tool.events.filterIsInstance<EditorEvent.Key>().none {
          it.key == Key.Z && it.type == KeyEventType.KeyDown
        }
      )
    }

    onRoot().performKeyInput {
      withKeyDown(Key.CtrlLeft) { withKeyDown(Key.ShiftLeft) { pressKey(Key.Z) } }
    }
    runOnIdle { assertEquals(listOf(id("a")), state.features.map { it.id }) }

    onRoot().performKeyInput { withKeyDown(Key.MetaLeft) { pressKey(Key.Z) } }
    runOnIdle { assertTrue(state.features.isEmpty()) }

    onRoot().performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.Y) } }
    runOnIdle { assertEquals(listOf(id("a")), state.features.map { it.id }) }

    onRoot().performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.Y) } }
    runOnIdle {
      assertEquals(1, state.features.size)
      val key =
        assertIs<EditorEvent.Key>(tool.events.first { it is EditorEvent.Key && it.key == Key.Y })
      assertEquals(setOf(KeyModifier.Ctrl), key.modifierKeys)
    }
  }

  @Test
  fun disabledUndoShortcutsReachTheTool() = runComposeUiTest {
    val tool = RecordingTool()
    val state = FeatureEditorState(initialTool = tool)
    state.add(point("a"))
    val focusRequester = FocusRequester()
    editor(state, undoShortcuts = false, focusRequester = focusRequester)
    runOnIdle { focusRequester.requestFocus() }

    onRoot().performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.Z) } }
    runOnIdle {
      assertEquals(1, state.features.size)
      val keys = tool.events.filterIsInstance<EditorEvent.Key>().filter { it.key == Key.Z }
      assertEquals(listOf(KeyEventType.KeyDown, KeyEventType.KeyUp), keys.map { it.type })
      assertTrue(keys.all { it.modifierKeys == setOf(KeyModifier.Ctrl) })
    }
  }

  @Test
  fun escapeCancelsAClaimedGesture() = runComposeUiTest {
    val tool = RecordingTool(claim = true)
    val state = FeatureEditorState(initialTool = tool)
    val focusRequester = FocusRequester()
    editor(state, focusRequester = focusRequester)
    runOnIdle { focusRequester.requestFocus() }

    onNodeWithTag("editor").performTouchInput { down(Offset(50.dp.toPx(), 50.dp.toPx())) }
    onRoot().performKeyInput { pressKey(Key.Escape) }
    runOnIdle {
      assertIs<EditorEvent.Cancel>(tool.events[1])
      assertFalse(state.gestureInProgress)
      assertTrue(tool.events.none { it is EditorEvent.Key && it.type == KeyEventType.KeyDown })
    }

    onNodeWithTag("editor").performTouchInput { up() }
    runOnIdle { assertEquals(3, tool.events.size) }
  }

  @Test
  fun disablingCancelsTheGestureInProgress() = runComposeUiTest {
    val tool = RecordingTool(claim = true)
    val state = FeatureEditorState(initialTool = tool)
    var enabled by mutableStateOf(true)
    val seen = mutableListOf<Seen>()
    editor(state, enabled = { enabled }, seen = seen)

    onNodeWithTag("editor").performTouchInput { down(Offset(50.dp.toPx(), 50.dp.toPx())) }
    runOnIdle { assertTrue(state.gestureInProgress) }

    enabled = false
    runOnIdle {
      assertIs<EditorEvent.Cancel>(tool.events.last())
      assertFalse(state.gestureInProgress)
    }

    onNodeWithTag("editor").performTouchInput { up() }
    onNodeWithTag("editor").performTouchInput { click(Offset(150.dp.toPx(), 150.dp.toPx())) }
    runOnIdle {
      assertEquals(2, tool.events.size)
      assertTrue(seen.drop(1).none { it.consumed })
    }
  }

  @Test
  fun visibleBoundsKeepAMarginAndFollowLargeMoves() = runComposeUiTest {
    val state = FeatureEditorState()
    var camera by mutableStateOf(0)
    var bounds by mutableStateOf<BoundingBox?>(null)
    editor(state, cameraKey = { camera }, visibleBounds = { bounds })
    runOnIdle { assertNull(state.visibleBounds) }

    bounds = BoundingBox(0.0, 0.0, 10.0, 10.0)
    camera++
    runOnIdle { assertBounds(-5.0, -5.0, 15.0, 15.0, state.visibleBounds) }

    bounds = BoundingBox(4.0, 4.0, 14.0, 14.0)
    camera++
    runOnIdle { assertBounds(-5.0, -5.0, 15.0, 15.0, state.visibleBounds) }

    bounds = BoundingBox(20.0, 20.0, 30.0, 30.0)
    camera++
    runOnIdle { assertBounds(15.0, 15.0, 35.0, 35.0, state.visibleBounds) }

    bounds = BoundingBox(24.0, 24.0, 26.0, 26.0)
    camera++
    runOnIdle { assertBounds(23.0, 23.0, 27.0, 27.0, state.visibleBounds) }
  }

  private fun assertBounds(
    west: Double,
    south: Double,
    east: Double,
    north: Double,
    actual: BoundingBox?,
  ) {
    val bounds = checkNotNull(actual) { "no visible bounds" }
    assertEquals(west, bounds.west, 1e-9)
    assertEquals(south, bounds.south, 1e-9)
    assertEquals(east, bounds.east, 1e-9)
    assertEquals(north, bounds.north, 1e-9)
  }
}
