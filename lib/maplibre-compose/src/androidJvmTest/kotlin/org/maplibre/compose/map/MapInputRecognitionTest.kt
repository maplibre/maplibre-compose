package org.maplibre.compose.map

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.MouseButton
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.moveBy
import androidx.compose.ui.test.moveTo
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.DpOffset
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.mlnffi.runPlainComposeUiTest

private const val RECOGNITION_MAP_TAG = "recognition-map"

/**
 * Gesture recognition and binding for [mapInput], hosted on a recording [GestureTarget].
 *
 * These cases do not create a MapLibre map. Camera effects that need Native live in
 * [MlnFfiMapInputTest].
 */
@OptIn(ExperimentalAtomicApi::class, ExperimentalTestApi::class)
class MapInputRecognitionTest {

  @Test
  fun a_press_that_jitters_within_the_slop_still_clicks() = runRecognitionTest { target ->
    mapNode().performMouseInput {
      moveTo(center)
      press()
      moveBy(Offset(1f, 0f))
      release()
    }
    awaitClicks(target, 1)
    assertEquals(0, target.moveCalls.size, "the jitter panned")
  }

  @Test
  fun a_press_past_the_slop_drags_instead_of_clicking() = runRecognitionTest { target ->
    mapNode().performMouseInput {
      moveTo(center)
      press()
      moveBy(Offset(60f, 0f))
      release()
    }
    waitUntil(timeoutMillis = TIMEOUT) { target.moveCalls.isNotEmpty() }
    assertEquals(0, target.clicks, "the drag reported a click")
  }

  @Test
  fun a_map_click_does_not_also_click_its_parent() {
    val parentClicks = AtomicInt(0)
    runRecognitionTest(parentOnClick = { parentClicks.incrementAndFetch() }) { target ->
      mapNode().performMouseInput { click(center) }
      awaitClicks(target, 1)
      waitForIdle()
      assertEquals(0, parentClicks.load())
    }
  }

  @Test
  fun a_map_long_click_does_not_also_long_click_its_parent() {
    val parentLongClicks = AtomicInt(0)
    runRecognitionTest(parentOnLongClick = { parentLongClicks.incrementAndFetch() }) { target ->
      val map = mapNode()
      map.performTouchInput { down(0, center) }
      mainClock.advanceTimeBy(1_000)
      waitUntil(timeoutMillis = TIMEOUT) { target.longClicks == 1 }
      map.performTouchInput { up(0) }
      waitForIdle()
      assertEquals(0, parentLongClicks.load())
    }
  }

  @Test
  fun a_tap_waits_for_a_second_one_that_could_still_arrive() = runRecognitionTest { target ->
    mainClock.autoAdvance = false
    try {
      mapNode().performTouchInput { click(center) }
      mainClock.advanceTimeByFrame()
      waitForIdle()
      assertEquals(0, target.clicks, "the tap reported before a double tap could rule it out")
    } finally {
      mainClock.autoAdvance = true
    }
    awaitClicks(target, 1)
  }

  @Test
  fun a_tap_reports_at_once_when_no_gesture_would_use_a_second_one() =
    runRecognitionTest(
      options = GestureOptions(isDoubleClickZoomEnabled = false, isQuickZoomEnabled = false)
    ) { target ->
      mainClock.autoAdvance = false
      try {
        mapNode().performTouchInput { click(center) }
        mainClock.advanceTimeByFrame()
        waitForIdle()
        assertEquals(1, target.clicks, "the tap waited for a double tap no gesture would use")
      } finally {
        mainClock.autoAdvance = true
      }
    }

  @Test
  fun a_bounce_faster_than_the_min_time_is_not_a_double_tap() = runRecognitionTest { target ->
    mapNode().performTouchInput {
      down(center)
      up()
      advanceEventTime(10)
      down(center)
      up()
    }
    mainClock.advanceTimeBy(1_000)
    waitForIdle()
    assertEquals(0, target.scaleCalls.size, "a bounce zoomed")
  }

  @Test
  fun a_second_tap_inside_the_bounce_window_still_clicks_when_no_gesture_awaits_it() =
    runRecognitionTest(
      options = GestureOptions(isDoubleClickZoomEnabled = false, isQuickZoomEnabled = false)
    ) { target ->
      mapNode().performTouchInput {
        down(center)
        up()
        advanceEventTime(10)
        down(center)
        up()
      }
      waitForIdle()
      assertEquals(2, target.clicks, "a bounce filter discarded a tap no gesture would pair")
    }

  @Test
  fun double_click_zooms_and_reports_its_first_click() = runRecognitionTest { target ->
    mapNode().performMouseInput { doubleClick() }
    waitUntil(timeoutMillis = TIMEOUT) { target.scaleCalls.isNotEmpty() }
    assertEquals(1, target.clicks, "a double click did not report exactly its first click")
    assertTrue(target.scaleCalls.any { it.scale > 1.0 }, "a double click did not zoom in")
  }

  @Test
  fun double_tap_zooms_without_reporting_the_first_tap() = runRecognitionTest { target ->
    mapNode().performTouchInput { doubleClick() }
    waitUntil(timeoutMillis = TIMEOUT) { target.scaleCalls.isNotEmpty() }
    mainClock.advanceTimeBy(1_000)
    waitForIdle()
    assertEquals(0, target.clicks, "a double tap leaked its first tap as a click")
    assertTrue(target.scaleCalls.any { it.scale > 1.0 }, "a double tap did not zoom in")
  }

  @Test
  fun position_locked_zooms_about_the_centre() =
    runRecognitionTest(options = GestureOptions.PositionLocked) { target ->
      mapNode().performMouseInput {
        doubleClick(Offset(width * 0.2f, height * 0.2f))
      }
      waitUntil(timeoutMillis = TIMEOUT) { target.scaleCalls.isNotEmpty() }
      assertTrue(
        target.scaleCalls.all { it.anchor == null },
        "a locked zoom anchored at the pointer",
      )
    }

  @Test
  fun arrow_keys_request_a_pan() = runRecognitionTest { target ->
    val map = mapNode()
    map.performMouseInput { click(Offset(10f, 10f)) }
    map.performKeyInput { pressKey(Key.DirectionRight) }
    waitUntil(timeoutMillis = TIMEOUT) { target.moveCalls.isNotEmpty() }
  }

  @Test
  fun a_hover_does_not_end_a_scroll_hold() = runRecognitionTest { target ->
    val map = mapNode()
    mainClock.autoAdvance = false
    try {
      map.performMouseInput { scroll(-1f) }
      mainClock.advanceTimeByFrame()
      waitForIdle()
      assertEquals(1, target.startedCount, "the wheel did not open a gesture")
      assertEquals(0, target.endedCount, "the move ended inside the scroll event that started it")

      map.performMouseInput { moveTo(center) }
      mainClock.advanceTimeByFrame()
      waitForIdle()
      assertEquals(0, target.endedCount, "a hover ended the scroll hold")

      mainClock.advanceTimeBy(SCROLL_HOLD_MILLIS + FRAME_MILLIS)
      waitUntil(timeoutMillis = TIMEOUT) { target.endedCount == 1 }
    } finally {
      mainClock.autoAdvance = true
    }
    assertTrue(target.scaleCalls.isNotEmpty(), "the wheel did not scale")
  }

  @Test
  fun the_scroll_hold_is_as_long_as_its_option_says() =
    runRecognitionTest(options = GestureOptions(scrollZoomHold = 600.milliseconds)) { target ->
      mainClock.autoAdvance = false
      try {
        mapNode().performMouseInput { scroll(-1f) }
        mainClock.advanceTimeByFrame()
        waitForIdle()
        assertEquals(0, target.endedCount)

        mainClock.advanceTimeBy(400)
        waitForIdle()
        assertEquals(0, target.endedCount, "a 400 ms gap ended a move held open for 600 ms")

        mainClock.advanceTimeBy(300)
        waitUntil(timeoutMillis = TIMEOUT) { target.endedCount == 1 }
      } finally {
        mainClock.autoAdvance = true
      }
    }

  @Test
  fun secondary_mouse_drag_requests_rotate_and_tilt() = runRecognitionTest { target ->
    mapNode().performMouseInput {
      moveTo(center)
      press(MouseButton.Secondary)
      moveBy(Offset(80f, -40f), delayMillis = 50)
      release(MouseButton.Secondary)
    }
    waitUntil(timeoutMillis = TIMEOUT) { target.rotateCalls.isNotEmpty() }
    assertEquals(0, target.moveCalls.size, "a secondary drag panned")
  }

  @Test
  fun pinch_requests_a_scale() = runRecognitionTest { target ->
    mapNode().performTouchInput {
      pinch(
        start0 = center - Offset(30f, 0f),
        start1 = center + Offset(30f, 0f),
        end0 = center - Offset(120f, 0f),
        end1 = center + Offset(120f, 0f),
        durationMillis = 200,
      )
    }
    waitUntil(timeoutMillis = TIMEOUT) { target.scaleCalls.any { it.scale > 1.0 } }
  }

  @Test
  fun two_finger_rotation_requests_bearing() = runRecognitionTest { target ->
    mapNode().performTouchInput {
      down(0, center - Offset(80f, 0f))
      down(1, center + Offset(80f, 0f))
      updatePointerTo(0, center - Offset(0f, 80f))
      updatePointerTo(1, center + Offset(0f, 80f))
      move()
      up(0)
      up(1)
    }
    waitUntil(timeoutMillis = TIMEOUT) { target.rotateCalls.any { it.bearingDelta != 0.0 } }
  }

  @Test
  fun two_finger_tap_requests_a_zoom_out() = runRecognitionTest { target ->
    mapNode().performTouchInput {
      down(0, center - Offset(40f, 0f))
      down(1, center + Offset(40f, 0f))
      up(0)
      up(1)
    }
    waitUntil(timeoutMillis = TIMEOUT) { target.scaleCalls.any { it.scale < 1.0 } }
  }

  @Test
  fun one_finger_swipe_requests_a_pan() = runRecognitionTest { target ->
    mapNode().performTouchInput {
      swipe(center, center + Offset(80f, 0f), durationMillis = 100)
    }
    waitUntil(timeoutMillis = TIMEOUT) { target.moveCalls.isNotEmpty() }
  }

  @Test
  fun quick_zoom_does_not_leak_its_first_tap() = runRecognitionTest { target ->
    mapNode().performTouchInput {
      click(center)
      advanceEventTime(SECOND_TAP_GAP_MILLIS)
      down(0, center)
      moveTo(0, center + Offset(0f, 100f), delayMillis = 100)
      up(0)
    }
    waitUntil(timeoutMillis = TIMEOUT) { target.scaleCalls.isNotEmpty() }
    mainClock.advanceTimeBy(1_000)
    waitForIdle()
    assertEquals(0, target.clicks, "a quick zoom leaked its first tap as a click")
  }

  @Test
  fun horizontal_motion_disqualifies_quick_zoom() = runRecognitionTest { target ->
    mapNode().performTouchInput {
      click(center)
      advanceEventTime(SECOND_TAP_GAP_MILLIS)
      down(0, center)
      moveTo(0, center + Offset(100f, 0f), delayMillis = 50)
      up(0)
    }
    mainClock.advanceTimeBy(500)
    waitForIdle()
    assertEquals(0, target.scaleCalls.size, "a rejected quick zoom scaled")
    assertEquals(0, target.moveCalls.size, "the disqualifying move panned")
  }

  private fun runRecognitionTest(
    options: GestureOptions = GestureOptions.Standard,
    parentOnClick: (() -> Unit)? = null,
    parentOnLongClick: (() -> Unit)? = null,
    body: ComposeUiTest.(RecordingGestureTarget) -> Unit,
  ) = runPlainComposeUiTest {
    val target = RecordingGestureTarget()
    setContent {
      val host: @Composable () -> Unit = { GestureHost(target, options) }
      when {
        parentOnLongClick != null ->
          Box(
            Modifier.fillMaxSize().combinedClickable(onClick = {}, onLongClick = parentOnLongClick)
          ) {
            host()
          }
        parentOnClick != null ->
          Box(Modifier.fillMaxSize().clickable(onClick = parentOnClick)) { host() }
        else -> host()
      }
    }
    waitForIdle()
    body(target)
  }

  private fun ComposeUiTest.awaitClicks(target: RecordingGestureTarget, count: Int) {
    waitUntil(timeoutMillis = TIMEOUT) { target.clicks == count }
  }

  /** Parent clickable nodes merge semantics. The map tag is only in the unmerged tree. */
  private fun ComposeUiTest.mapNode(): SemanticsNodeInteraction =
    onNodeWithTag(RECOGNITION_MAP_TAG, useUnmergedTree = true)

  private companion object {
    const val TIMEOUT = 5_000L
    const val FRAME_MILLIS = 16L
    const val SECOND_TAP_GAP_MILLIS = 80L
    val SCROLL_HOLD_MILLIS = GestureOptions.Standard.scrollZoomHold.inWholeMilliseconds
  }
}

@Composable
private fun GestureHost(target: GestureTarget, options: GestureOptions) {
  val density = LocalDensity.current
  val focusRequester = remember { FocusRequester() }
  val inputScope = rememberCoroutineScope()
  val continuation = remember(inputScope) { GestureContinuation(inputScope) }
  Box(
    Modifier.fillMaxSize()
      .testTag(RECOGNITION_MAP_TAG)
      .mapInput(target, options, density, focusRequester, continuation)
  )
}

/** Records every [GestureTarget] call so recognition tests can assert without a map. */
private class RecordingGestureTarget : GestureTarget {
  var clicks = 0
  var longClicks = 0
  var startedCount = 0
  var endedCount = 0
  val moveCalls = mutableListOf<Offset>()
  val scaleCalls = mutableListOf<ScaleCall>()
  val rotateCalls = mutableListOf<RotateCall>()

  private var nextToken = 1L
  private var camera = CameraPosition()

  override fun cancelTransitions() = Unit

  override fun getCameraPosition(): CameraPosition = camera

  override fun onGestureStarted(): GestureToken {
    startedCount += 1
    return GestureToken(nextToken++)
  }

  override fun onGestureEnded(token: GestureToken) {
    endedCount += 1
  }

  override fun onPrimaryClick(offset: DpOffset) {
    clicks += 1
  }

  override fun onSecondaryClick(offset: DpOffset) {
    longClicks += 1
  }

  override fun moveBy(
    deltaX: Double,
    deltaY: Double,
    duration: Duration,
    gestureToken: GestureToken?,
  ) {
    moveCalls += Offset(deltaX.toFloat(), deltaY.toFloat())
  }

  override fun scaleBy(
    scale: Double,
    anchor: DpOffset?,
    duration: Duration,
    gestureToken: GestureToken?,
  ) {
    scaleCalls += ScaleCall(scale, anchor)
  }

  override fun rotateAndPitchBy(
    bearingDelta: Double,
    pitchDelta: Double,
    duration: Duration,
    anchor: DpOffset?,
    gestureToken: GestureToken?,
  ) {
    rotateCalls += RotateCall(bearingDelta, pitchDelta)
  }

  override suspend fun moveByAwaitingTransition(
    deltaX: Double,
    deltaY: Double,
    duration: Duration,
    gestureToken: GestureToken,
  ) {
    moveBy(deltaX, deltaY, duration, gestureToken)
  }

  override suspend fun scaleByAwaitingTransition(
    scale: Double,
    anchor: DpOffset?,
    duration: Duration,
    gestureToken: GestureToken,
  ) {
    scaleBy(scale, anchor, duration, gestureToken)
  }

  override suspend fun rotateAndPitchByAwaitingTransition(
    bearingDelta: Double,
    pitchDelta: Double,
    duration: Duration,
    gestureToken: GestureToken,
  ) {
    rotateAndPitchBy(bearingDelta, pitchDelta, duration, gestureToken = gestureToken)
  }

  data class ScaleCall(val scale: Double, val anchor: DpOffset?)

  data class RotateCall(val bearingDelta: Double, val pitchDelta: Double)
}
