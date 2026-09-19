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
import org.maplibre.compose.interaction.PointerButton
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.Point
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

  /** Claims every press and drags the point feature "a" with the pointer. */
  private class PointDragTool : EditorTool {
    override fun onEvent(event: EditorEvent, state: FeatureEditorState): Boolean =
      when (event) {
        is EditorEvent.Press -> true
        is EditorEvent.Drag ->
          state.moveVertex(VertexRef(id("a"), emptyList()), event.pointer.position, event.step)
        else -> false
      }
  }

  private fun FeatureEditorState.pointA(): Position =
    (feature(id("a"))!!.geometry as Point).coordinates

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

  @Test
  fun chainedEditorsDeliverAClaimedPointerOnlyToTheFirst() = runComposeUiTest {
    val first = RecordingTool(claim = true)
    val second = RecordingTool(claim = true)
    val firstState = FeatureEditorState(initialTool = first)
    val secondState = FeatureEditorState(initialTool = second)
    setContent {
      Box(
        Modifier.size(200.dp)
          .testTag("editor")
          .featureEditor(firstState, ::project, ::unproject, { null }, { null })
          .featureEditor(secondState, ::project, ::unproject, { null }, { null })
      )
    }

    onNodeWithTag("editor").performTouchInput { click(Offset(50.dp.toPx(), 50.dp.toPx())) }
    runOnIdle {
      assertEquals(listOf("Press", "Tap"), first.events.map { it::class.simpleName })
      assertEquals(emptyList(), second.events)
    }

    first.claim = false
    first.consumeTap = true
    second.claim = false
    first.events.clear()
    onNodeWithTag("editor").performTouchInput { click(Offset(150.dp.toPx(), 150.dp.toPx())) }
    runOnIdle {
      assertEquals(listOf("Press", "Tap"), first.events.map { it::class.simpleName })
      assertEquals(listOf("Press"), second.events.map { it::class.simpleName })
    }
  }

  @Test
  fun anAncestorConsumingThePressInInitialBlocksTheEditor() = runComposeUiTest {
    val tool = RecordingTool(claim = true)
    val state = FeatureEditorState(initialTool = tool)
    setContent {
      Box(
        Modifier.size(200.dp).pointerInput(Unit) {
          awaitPointerEventScope {
            while (true) {
              awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
            }
          }
        }
      ) {
        Box(
          Modifier.fillMaxSize()
            .testTag("editor")
            .featureEditor(state, ::project, ::unproject, { null }, { null })
        )
      }
    }

    onNodeWithTag("editor").performTouchInput { click(Offset(50.dp.toPx(), 50.dp.toPx())) }
    runOnIdle {
      assertEquals(emptyList(), tool.events)
      assertFalse(state.gestureInProgress)
    }
  }

  @Test
  fun undoShortcutsWaitForTheClaimedGestureToEnd() = runComposeUiTest {
    val state = FeatureEditorState(listOf(point("a", 10.0, -10.0)), initialTool = PointDragTool())
    val focusRequester = FocusRequester()
    editor(state, focusRequester = focusRequester)
    runOnIdle { focusRequester.requestFocus() }

    onNodeWithTag("editor").performTouchInput { down(Offset(100.dp.toPx(), 100.dp.toPx())) }
    onNodeWithTag("editor").performTouchInput { moveBy(Offset(60.dp.toPx(), 0f)) }
    runOnIdle { assertEquals(16.0, state.pointA().longitude, 1e-9) }

    onRoot().performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.Z) } }
    runOnIdle {
      assertEquals(16.0, state.pointA().longitude, 1e-9)
      assertTrue(state.canUndo)
      assertFalse(state.canRedo)
    }

    onNodeWithTag("editor").performTouchInput { up() }
    onRoot().performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.Z) } }
    runOnIdle {
      assertEquals(10.0, state.pointA().longitude, 1e-9)
      assertFalse(state.canUndo)
    }
  }

  @Test
  fun toolKeysWaitForTheClaimedGestureToEnd() = runComposeUiTest {
    val original = square("a", originLat = -20.0)
    val state = FeatureEditorState(listOf(original))
    state.selection = setOf(id("a"))
    val focusRequester = FocusRequester()
    editor(state, focusRequester = focusRequester)
    runOnIdle { focusRequester.requestFocus() }

    onNodeWithTag("editor").performTouchInput { down(Offset(100.dp.toPx(), 100.dp.toPx())) }
    onNodeWithTag("editor").performTouchInput { moveBy(Offset(30.dp.toPx(), 0f)) }
    onNodeWithTag("editor").performTouchInput { moveBy(Offset(30.dp.toPx(), 0f)) }
    runOnIdle {
      assertTrue(state.gestureInProgress)
      assertEquals(HandleKind.Vertex, state.activeHandle?.kind)
      assertEquals(16.0, state.activeHandle!!.position.longitude, 1e-9)
    }

    onRoot().performKeyInput { pressKey(Key.DirectionRight) }
    onRoot().performKeyInput { pressKey(Key.Delete) }
    onNodeWithTag("editor").performTouchInput { moveBy(Offset(10.dp.toPx(), 0f)) }
    runOnIdle {
      assertEquals(1, state.features.size)
      assertEquals(17.0, state.activeHandle!!.position.longitude, 1e-9)
    }

    onRoot().performKeyInput { pressKey(Key.Escape) }
    runOnIdle {
      assertEquals(listOf(original), state.features)
      assertFalse(state.canUndo)
      assertFalse(state.gestureInProgress)
    }

    onNodeWithTag("editor").performTouchInput { up() }
    onRoot().performKeyInput { pressKey(Key.Delete) }
    runOnIdle { assertEquals(emptyList(), state.features) }
  }

  @Test
  fun aMoveALaterNodeConsumesDropsTheTap() = runComposeUiTest {
    val tool = RecordingTool(consumeTap = true)
    val state = FeatureEditorState(initialTool = tool)
    val seen = mutableListOf<Seen>()
    setContent {
      Box(
        Modifier.size(200.dp)
          .testTag("editor")
          .featureEditor(state, ::project, ::unproject, { null }, { null })
      ) {
        Box(
          Modifier.fillMaxSize().pointerInput(Unit) {
            awaitPointerEventScope {
              var origin = Offset.Zero
              while (true) {
                val event = awaitPointerEvent(PointerEventPass.Main)
                event.changes.forEach {
                  if (!it.previousPressed) origin = it.position
                  val moved = it.pressed && it.previousPressed
                  if (moved && (it.position - origin).getDistance() > 4.dp.toPx()) it.consume()
                  seen += Seen(it.pressed, it.previousPressed, it.isConsumed)
                }
              }
            }
          }
        )
      }
    }

    onNodeWithTag("editor").performTouchInput { down(Offset(50.dp.toPx(), 50.dp.toPx())) }
    onNodeWithTag("editor").performTouchInput { moveBy(Offset(6.dp.toPx(), 0f)) }
    onNodeWithTag("editor").performTouchInput { up() }
    runOnIdle {
      assertEquals(listOf("Press"), tool.events.map { it::class.simpleName })
      assertEquals(Seen(true, true, true), seen[1])
      assertEquals(Seen(false, true, false), seen.last())
    }

    onNodeWithTag("editor").performTouchInput { down(Offset(50.dp.toPx(), 50.dp.toPx())) }
    onNodeWithTag("editor").performTouchInput { moveBy(Offset(2.dp.toPx(), 0f)) }
    onNodeWithTag("editor").performTouchInput { up() }
    runOnIdle {
      assertIs<EditorEvent.Tap>(tool.events.last())
      assertEquals(Seen(false, true, true), seen.last())
    }
  }

  @Test
  fun endingAClaimedGestureClearsTheErrorOfARejectedFrame() = runComposeUiTest {
    val state =
      FeatureEditorState(
        listOf(point("a", 10.0, -10.0)),
        initialTool = PointDragTool(),
        validate = { if ((it.geometry as Point).longitude > 12) "too far" else null },
      )
    editor(state)

    onNodeWithTag("editor").performTouchInput { down(Offset(100.dp.toPx(), 100.dp.toPx())) }
    onNodeWithTag("editor").performTouchInput { moveBy(Offset(60.dp.toPx(), 0f)) }
    runOnIdle {
      assertEquals("too far", state.validationError)
      assertEquals(10.0, state.pointA().longitude, 1e-9)
    }

    onNodeWithTag("editor").performTouchInput { up() }
    runOnIdle {
      assertNull(state.validationError)
      assertFalse(state.gestureInProgress)
    }
  }

  @Test
  fun swappingTheStateSeedsItsVisibleBounds() = runComposeUiTest {
    var state by mutableStateOf(FeatureEditorState())
    val first = state
    setContent {
      Box(
        Modifier.size(200.dp)
          .featureEditor(
            state,
            ::project,
            ::unproject,
            { null },
            { BoundingBox(0.0, 0.0, 10.0, 10.0) },
          )
      )
    }
    runOnIdle { assertBounds(-5.0, -5.0, 15.0, 15.0, first.visibleBounds) }

    val second = FeatureEditorState()
    state = second
    runOnIdle { assertBounds(-5.0, -5.0, 15.0, 15.0, second.visibleBounds) }
  }

  @Test
  fun leavingTheMapOrDisablingAfterAHoverDeliversHoverEndOnce() = runComposeUiTest {
    val tool = RecordingTool()
    val state = FeatureEditorState(initialTool = tool)
    var enabled by mutableStateOf(true)
    editor(state, enabled = { enabled })

    onNodeWithTag("editor").performMouseInput { moveTo(Offset(10.dp.toPx(), 10.dp.toPx())) }
    onNodeWithTag("editor").performMouseInput { exit() }
    runOnIdle {
      assertIs<EditorEvent.Hover>(tool.events[tool.events.lastIndex - 1])
      assertIs<EditorEvent.HoverEnd>(tool.events.last())
      assertEquals(1, tool.events.count { it is EditorEvent.HoverEnd })
    }

    enabled = false
    runOnIdle { assertEquals(1, tool.events.count { it is EditorEvent.HoverEnd }) }

    enabled = true
    onNodeWithTag("editor").performMouseInput { moveTo(Offset(20.dp.toPx(), 20.dp.toPx())) }
    runOnIdle { assertIs<EditorEvent.Hover>(tool.events.last()) }
    enabled = false
    runOnIdle {
      assertIs<EditorEvent.HoverEnd>(tool.events.last())
      assertEquals(2, tool.events.count { it is EditorEvent.HoverEnd })
    }
  }

  @Test
  fun hoverOffTheGlobeEndsTheHover() = runComposeUiTest {
    val tool = RecordingTool()
    val state = FeatureEditorState(listOf(point("a", 10.0, -10.0)), initialTool = tool)
    var unprojectable = true
    setContent {
      Box(
        Modifier.size(200.dp)
          .testTag("editor")
          .featureEditor(
            state,
            ::project,
            { if (unprojectable) unproject(it) else null },
            { null },
            { null },
          )
      )
    }

    onNodeWithTag("editor").performMouseInput { moveTo(Offset(100.dp.toPx(), 100.dp.toPx())) }
    runOnIdle { assertIs<FeatureHit>(state.hover) }

    unprojectable = false
    onNodeWithTag("editor").performMouseInput { moveTo(Offset(110.dp.toPx(), 110.dp.toPx())) }
    runOnIdle {
      assertNull(state.hover)
      assertIs<EditorEvent.HoverEnd>(tool.events.last())
    }

    onNodeWithTag("editor").performMouseInput { moveTo(Offset(120.dp.toPx(), 120.dp.toPx())) }
    runOnIdle { assertEquals(1, tool.events.count { it is EditorEvent.HoverEnd }) }

    unprojectable = true
    onNodeWithTag("editor").performMouseInput { moveTo(Offset(100.dp.toPx(), 100.dp.toPx())) }
    runOnIdle {
      assertIs<EditorEvent.Hover>(tool.events.last())
      assertIs<FeatureHit>(state.hover)
    }
  }

  @Test
  fun aPressOffTheMapLeavesNoStaleHitOrButtonsForTheTap() = runComposeUiTest {
    val tool = RecordingTool(consumeTap = true)
    val state = FeatureEditorState(listOf(point("a", 10.0, -10.0)), initialTool = tool)
    var unprojectable = true
    setContent {
      Box(
        Modifier.size(200.dp)
          .testTag("editor")
          .featureEditor(
            state,
            ::project,
            { if (unprojectable) unproject(it) else null },
            { null },
            { null },
          )
      )
    }

    onNodeWithTag("editor").performMouseInput { click(Offset(100.dp.toPx(), 100.dp.toPx())) }
    runOnIdle {
      val tap = tool.events.filterIsInstance<EditorEvent.Tap>().single()
      assertIs<FeatureHit>(tap.hit)
      assertEquals(setOf(PointerButton.Primary), tap.pointer.buttons)
    }

    unprojectable = false
    onNodeWithTag("editor").performTouchInput { down(Offset(100.dp.toPx(), 100.dp.toPx())) }
    runOnIdle { unprojectable = true }
    onNodeWithTag("editor").performTouchInput { up() }
    runOnIdle {
      val tap = assertIs<EditorEvent.Tap>(tool.events.last())
      assertNull(tap.hit)
      assertEquals(emptySet(), tap.pointer.buttons)
      assertEquals(2, tool.events.count { it is EditorEvent.Tap })
    }
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
