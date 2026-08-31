@file:OptIn(ExperimentalAtomicApi::class)

package org.maplibre.compose.map

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.MouseButton
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.moveBy
import androidx.compose.ui.test.moveTo
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.unit.dp
import co.touchlab.kermit.Logger
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import org.maplibre.compose.camera.CameraMoveReason
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.mlnffi.FfiTestPlatform
import org.maplibre.compose.mlnffi.MlnFfiRuntimeOptions
import org.maplibre.compose.mlnffi.runFfiComposeUiTest
import org.maplibre.compose.mlnffi.setFfiTestMapContent
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.util.ClickResult
import org.maplibre.spatialk.geojson.Position

@OptIn(ExperimentalTestApi::class)
class MlnFfiMapInputTest {

  private val cacheFile = FfiTestPlatform.createCacheFile()

  /** Every click the map reported, including the one [runInputTest] uses to take focus. */
  private val clicks = mutableListOf<Position>()
  private val longClicks = mutableListOf<Position>()

  private val runtimeOptions =
    MlnFfiRuntimeOptions(
      cacheFile = cacheFile,
      maximumCacheSizeBytes = null,
      logger = Logger.withTag("input-test"),
    )

  @AfterTest
  fun cleanUp() {
    FfiTestPlatform.deleteCacheFile(cacheFile)
  }

  @Test
  fun arrow_keys_pan_the_map() = runInputTest { camera ->
    val before = camera.position.target.longitude
    onRoot().performKeyInput { pressKey(Key.DirectionRight) }
    waitUntil(timeoutMillis = TIMEOUT) { camera.position.target.longitude != before }
    assertEquals(CameraMoveReason.GESTURE, camera.moveReason)
  }

  @Test
  fun a_hover_does_not_cancel_an_arrow_key_pan() =
    runInputTest(gestures = GestureOptions.Standard.copy(animationDuration = 2.seconds)) { camera ->
      val before = camera.position.target.longitude
      onRoot().performKeyInput { pressKey(Key.DirectionRight) }
      waitUntil(timeoutMillis = TIMEOUT) { camera.isCameraMoving }

      mainClock.autoAdvance = false
      try {
        onRoot().performMouseInput { moveTo(center) }
        mainClock.advanceTimeByFrame()
        // waitForIdle() would wait until overlay layout stops invalidating, which follows
        // MapPresentation viewport replacements through the rest of this native ease.
        assertTrue(camera.isCameraMoving, "a hover cancelled the keyboard pan")
      } finally {
        mainClock.autoAdvance = true
      }
      // Camera eases advance on map frames, which the host produces while the Compose clock runs.
      waitUntil(timeoutMillis = TIMEOUT) { !camera.isCameraMoving }
      assertTrue(
        camera.position.target.longitude != before,
        "the pan did not finish after the hover",
      )
    }

  @Test
  fun plus_and_minus_zoom_the_map() = runInputTest { camera ->
    onRoot().performKeyInput { pressKey(Key.Equals) }
    // A zoom transition only advances while frames render.
    awaitZoom(camera, START_ZOOM + 1.0)

    onRoot().performKeyInput { pressKey(Key.Minus) }
    awaitZoom(camera, START_ZOOM)
  }

  @Test
  fun double_click_zooms_in() = runInputTest { camera ->
    onRoot().performMouseInput { doubleClick() }
    awaitZoom(camera, START_ZOOM + 1.0)
    assertEquals(CameraMoveReason.GESTURE, camera.moveReason)
  }

  /** A mouse never waits for a second click; only touch taps do. */
  @Test
  fun double_click_still_reports_its_first_click() =
    runInputTest(focusWithMouse = false) { camera ->
      onRoot().performMouseInput { doubleClick() }
      awaitZoom(camera, START_ZOOM + 1.0)
      mainClock.advanceTimeBy(1_000)
      waitForIdle()
      assertEquals(1, clicks.size, "a double click did not report exactly its first click")
    }

  @Test
  fun double_click_eases_rather_than_jumping() = runInputTest { camera ->
    val target = START_ZOOM + 1.0
    var sawIntermediate = false
    var endedWhileIntermediate = false

    onRoot().performMouseInput { doubleClick() }
    waitUntil(timeoutMillis = TIMEOUT) {
      val zoom = camera.position.zoom
      if (zoom > START_ZOOM + 0.01 && zoom < target - 0.01) {
        sawIntermediate = true
        if (!camera.isCameraMoving) endedWhileIntermediate = true
      }
      zoom >= target - ZOOM_TOLERANCE
    }

    assertTrue(sawIntermediate, "the zoom went straight to $target, so it did not animate")
    assertFalse(endedWhileIntermediate, "the gesture ended while its zoom was still easing")
  }

  @Test
  fun mouse_wheel_zooms_at_the_pointer() = runInputTest { camera ->
    onRoot().performMouseInput {
      moveTo(Offset(width * 0.25f, height * 0.25f))
      scroll(-1f)
    }
    awaitZoom(camera, START_ZOOM + GestureOptions.Standard.scrollZoomStep)
  }

  @Test
  fun mouse_wheel_reports_a_gesture_camera_move() = runInputTest { camera ->
    mainClock.autoAdvance = false
    try {
      onRoot().performMouseInput { scroll(-1f) }

      waitUntil(timeoutMillis = TIMEOUT) { camera.moveReason == CameraMoveReason.GESTURE }

      mainClock.advanceTimeBy(SCROLL_HOLD_MILLIS + FRAME_MILLIS)
      waitUntil(timeoutMillis = TIMEOUT) { !camera.isCameraMoving }
    } finally {
      mainClock.autoAdvance = true
    }
  }

  @Test
  fun mouse_wheel_burst_is_one_camera_move() = runInputTest { camera ->
    mainClock.autoAdvance = false
    try {
      onRoot().performMouseInput {
        moveTo(center)
        scroll(-1f)
      }
      mainClock.advanceTimeByFrame()
      waitForIdle()
      assertTrue(camera.isCameraMoving, "the move ended inside the scroll event that started it")

      val firstNotchAt = mainClock.currentTime
      while (mainClock.currentTime - firstNotchAt < SCROLL_HOLD_MILLIS / 2) {
        mainClock.advanceTimeByFrame()
        waitForIdle()
        assertTrue(camera.isCameraMoving, "the burst was reported as more than one camera move")
        assertEquals(CameraMoveReason.GESTURE, camera.moveReason)
      }

      onRoot().performMouseInput { scroll(-1f) }
      mainClock.advanceTimeByFrame()
      waitForIdle()
      assertTrue(camera.isCameraMoving, "the second notch did not continue the move")

      mainClock.advanceTimeBy(SCROLL_HOLD_MILLIS + FRAME_MILLIS)
      waitUntil(timeoutMillis = TIMEOUT) { !camera.isCameraMoving }
    } finally {
      mainClock.autoAdvance = true
    }

    awaitZoom(camera, START_ZOOM + 2 * GestureOptions.Standard.scrollZoomStep)
  }

  @Test
  fun mouse_wheel_move_outlives_the_event_but_not_the_hold() = runInputTest { camera ->
    mainClock.autoAdvance = false
    try {
      val notchAt = mainClock.currentTime
      onRoot().performMouseInput { scroll(-1f) }
      mainClock.advanceTimeByFrame()
      waitForIdle()
      assertTrue(camera.isCameraMoving, "the move ended inside the scroll event that started it")

      // A frame short of the hold, since advanceTimeBy rounds up to whole frames.
      mainClock.advanceTimeBy(SCROLL_HOLD_MILLIS - (mainClock.currentTime - notchAt) - FRAME_MILLIS)
      waitForIdle()
      assertTrue(camera.isCameraMoving, "the move ended before the hold elapsed")

      mainClock.advanceTimeBy(2 * FRAME_MILLIS)
      waitUntil(timeoutMillis = TIMEOUT) { !camera.isCameraMoving }
    } finally {
      mainClock.autoAdvance = true
    }
  }

  @Test
  fun the_scroll_hold_is_as_long_as_its_option_says() =
    runInputTest(gestures = GestureOptions(scrollZoomHold = 600.milliseconds)) { camera ->
      mainClock.autoAdvance = false
      try {
        onRoot().performMouseInput { scroll(-1f) }
        mainClock.advanceTimeByFrame()
        waitForIdle()

        mainClock.advanceTimeBy(400)
        waitForIdle()
        assertTrue(camera.isCameraMoving, "a 400 ms gap ended a move held open for 600 ms")

        mainClock.advanceTimeBy(300)
        waitUntil(timeoutMillis = TIMEOUT) { !camera.isCameraMoving }
      } finally {
        mainClock.autoAdvance = true
      }
    }

  @Test
  fun secondary_mouse_drag_rotates_and_tilts() = runInputTest { camera ->
    val before = camera.position
    onRoot().performMouseInput {
      moveTo(center)
      press(MouseButton.Secondary)
      moveBy(Offset(80f, -40f), delayMillis = 50)
      release(MouseButton.Secondary)
    }
    waitUntil(timeoutMillis = TIMEOUT) {
      camera.position.bearing != before.bearing && camera.position.tilt != before.tilt
    }
  }

  @Test
  fun shift_and_arrow_keys_rotate_and_tilt() = runInputTest { camera ->
    onRoot().performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.DirectionRight) } }
    waitUntil(timeoutMillis = TIMEOUT) { camera.position.bearing > 0.0 }

    onRoot().performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.DirectionUp) } }
    waitUntil(timeoutMillis = TIMEOUT) { camera.position.tilt > 0.0 }
  }

  @Test
  fun a_press_that_jitters_within_the_slop_still_clicks() = runInputTest { camera ->
    val longitudeBefore = camera.position.target.longitude
    val clicksBefore = clicks.size

    onRoot().performMouseInput {
      // Away from the focus click, so this does not read as the second half of a double click.
      moveTo(Offset(width * 0.25f, height * 0.25f))
      press()
      moveBy(Offset(1f, 0f))
      release()
    }

    awaitClickCounts("the press was not reported as a map click") { clicks.size > clicksBefore }
    assertEquals(
      longitudeBefore,
      camera.position.target.longitude,
      TARGET_TOLERANCE,
      "the jitter panned the map",
    )
  }

  @Test
  fun a_press_past_the_slop_drags_instead_of_clicking() = runInputTest { camera ->
    val longitudeBefore = camera.position.target.longitude
    val clicksBefore = clicks.size

    onRoot().performMouseInput {
      moveTo(Offset(width * 0.25f, height * 0.25f))
      press()
      moveBy(Offset(60f, 0f))
      release()
    }

    waitUntil(timeoutMillis = TIMEOUT) { camera.position.target.longitude != longitudeBefore }
    assertEquals(clicksBefore, clicks.size, "the drag reported a click")
  }

  @Test
  fun a_map_click_does_not_also_click_its_parent() {
    val parentClicks = AtomicInt(0)

    runInputTest(focusWithMouse = false, parentOnClick = { parentClicks.incrementAndFetch() }) {
      onRoot().performMouseInput { click(center) }
      awaitClickCounts("the tap was not reported as a map click") { clicks.size == 1 }
      waitForIdle()

      assertEquals(0, parentClicks.load())
    }
  }

  @Test
  fun a_map_long_click_does_not_also_long_click_its_parent() {
    val parentLongClicks = AtomicInt(0)

    runInputTest(
      focusWithMouse = false,
      parentOnLongClick = { parentLongClicks.incrementAndFetch() },
    ) {
      val map = onRoot()
      map.performTouchInput { down(0, center) }
      mainClock.advanceTimeBy(1_000)
      awaitClickCounts("the press was not reported as a map long click") { longClicks.size == 1 }
      map.performTouchInput { up(0) }
      waitForIdle()

      assertEquals(0, parentLongClicks.load())
    }
  }

  @Test
  fun a_long_click_on_a_paired_second_tap_does_not_report_the_first_tap() =
    runInputTest(
      gestures = GestureOptions(isQuickZoomEnabled = false),
      focusWithMouse = false,
    ) {
      val map = onRoot()
      map.performTouchInput {
        down(center)
        up()
        advanceEventTime(SECOND_TAP_GAP_MILLIS)
        down(center)
      }
      mainClock.advanceTimeBy(1_000)
      awaitClickCounts("the press was not reported as a map long click") { longClicks.size == 1 }
      map.performTouchInput { up() }
      waitForIdle()
      assertEquals(0, clicks.size, "a paired long click reported the first tap as a map click")

      map.performTouchInput { click(center) }
      mainClock.advanceTimeBy(1_000)
      waitForIdle()
      assertEquals(1, clicks.size, "the next tap inherited a stale claimed first tap")
      assertEquals(1, longClicks.size)
    }

  @Test
  fun one_finger_pans_the_map() =
    runInputTest(focusWithMouse = false) { camera ->
      val before = camera.position.target.longitude
      onRoot().performTouchInput { swipe(center, center + Offset(80f, 0f), durationMillis = 100) }
      waitUntil(timeoutMillis = TIMEOUT) { camera.position.target.longitude != before }
    }

  @Test
  fun pinch_zooms_the_map() =
    runInputTest(focusWithMouse = false) { camera ->
      onRoot().performTouchInput {
        pinch(
          start0 = center - Offset(30f, 0f),
          start1 = center + Offset(30f, 0f),
          end0 = center - Offset(120f, 0f),
          end1 = center + Offset(120f, 0f),
          durationMillis = 200,
        )
      }
      waitUntil(timeoutMillis = TIMEOUT) { camera.position.zoom > START_ZOOM + 0.5 }
    }

  @Test
  fun two_finger_rotation_rotates_the_map() =
    runInputTest(focusWithMouse = false) { camera ->
      val before = camera.position.bearing
      onRoot().performTouchInput {
        down(0, center - Offset(80f, 0f))
        down(1, center + Offset(80f, 0f))
        updatePointerTo(0, center - Offset(0f, 80f))
        updatePointerTo(1, center + Offset(0f, 80f))
        move()
        up(0)
        up(1)
      }
      waitUntil(timeoutMillis = TIMEOUT) { camera.position.bearing != before }
    }

  @Test
  fun touch_rotation_preserves_its_focal_point_and_mouse_still_works_afterward() =
    runInputTest { camera ->
      val anchor = Offset(onRoot().fetchSemanticsNode().size.width * 0.3f, 240f)
      val clicksBefore = clicks.size
      onRoot().performMouseInput { click(anchor) }
      awaitClickCounts("the mouse click was not reported") { clicks.size == clicksBefore + 1 }
      val positionBefore = clicks.last()
      val bearingBefore = camera.position.bearing

      onRoot().performTouchInput {
        down(0, anchor - Offset(70f, 0f))
        down(1, anchor + Offset(70f, 0f))
        updatePointerTo(0, anchor - Offset(0f, 70f))
        updatePointerTo(1, anchor + Offset(0f, 70f))
        move(delayMillis = 20)
        up(0)
        up(1)
      }
      waitUntil(timeoutMillis = TIMEOUT) { camera.position.bearing != bearingBefore }

      onRoot().performMouseInput { click(anchor) }
      awaitClickCounts("the mouse click after the rotation was not reported") {
        clicks.size == clicksBefore + 2
      }
      val positionAfter = clicks.last()
      assertEquals(positionBefore.longitude, positionAfter.longitude, 1e-5, "longitude")
      assertEquals(positionBefore.latitude, positionAfter.latitude, 1e-5, "latitude")
    }

  @Test
  fun two_finger_shove_tilts_the_map() =
    runInputTest(focusWithMouse = false) { camera ->
      onRoot().performTouchInput {
        down(0, center - Offset(80f, 0f))
        down(1, center + Offset(80f, 0f))
        // Cross pan's 4 dp threshold before shove's 16 dp threshold, just as a real stream does.
        repeat(5) {
          updatePointerBy(0, Offset(0f, -5f))
          updatePointerBy(1, Offset(0f, -5f))
          move(delayMillis = 20)
        }
        up(0)
        up(1)
      }
      waitUntil(timeoutMillis = TIMEOUT) { camera.position.tilt > 0.0 }
    }

  @Test
  fun one_finger_takeover_discards_deferred_pinch_velocity() =
    runInputTest(
      gestures =
        GestureOptions.Standard.copy(isFlingEnabled = false, isRotateVelocityEnabled = false),
      focusWithMouse = false,
    ) { camera ->
      val map = onRoot()
      map.performTouchInput {
        down(0, center - Offset(40f, 0f))
        down(1, center + Offset(40f, 0f))
        updatePointerTo(0, center - Offset(120f, 0f))
        updatePointerTo(1, center + Offset(120f, 0f))
        move(delayMillis = 30)
        up(1)
      }
      waitUntil(timeoutMillis = TIMEOUT) { camera.position.zoom > START_ZOOM + 0.5 }

      val longitudeBeforeTakeover = camera.position.target.longitude
      map.performTouchInput { moveTo(0, center + Offset(100f, 0f), delayMillis = 100) }
      waitUntil(timeoutMillis = TIMEOUT) {
        camera.position.target.longitude != longitudeBeforeTakeover
      }
      val zoomAtTakeover = camera.position.zoom

      map.performTouchInput { up(0) }
      mainClock.advanceTimeBy(1_000)
      waitForIdle()

      assertEquals(
        zoomAtTakeover,
        camera.position.zoom,
        ZOOM_TOLERANCE,
        "releasing the one-finger pan resumed stale pinch momentum",
      )
    }

  @Test
  fun two_finger_tap_zooms_out() =
    runInputTest(focusWithMouse = false) { camera ->
      onRoot().performTouchInput {
        down(0, center - Offset(40f, 0f))
        down(1, center + Offset(40f, 0f))
        up(0)
        up(1)
      }
      awaitZoom(camera, START_ZOOM - 1.0)
      assertEquals(CameraMoveReason.GESTURE, camera.moveReason)
    }

  @Test
  fun double_tap_zooms_the_map() =
    runInputTest(focusWithMouse = false) { camera ->
      onRoot().performTouchInput { doubleClick() }
      awaitZoom(camera, START_ZOOM + 1.0)
      assertEquals(CameraMoveReason.GESTURE, camera.moveReason)
      mainClock.advanceTimeBy(1_000)
      waitForIdle()
      assertEquals(0, clicks.size, "a double tap leaked its first tap as a map click")
    }

  @Test
  fun a_second_down_inside_the_timeout_still_zooms_after_a_slow_up() =
    runInputTest(focusWithMouse = false) { camera ->
      onRoot().performTouchInput {
        down(center)
        up()
        advanceEventTime(SECOND_TAP_GAP_MILLIS)
        down(center)
        advanceEventTime(400)
        up()
      }
      awaitZoom(camera, START_ZOOM + 1.0)
      mainClock.advanceTimeBy(1_000)
      waitForIdle()
      assertEquals(0, clicks.size, "a held second tap leaked the first tap as a map click")
    }

  @Test
  fun a_bounce_faster_than_the_min_time_is_not_a_double_tap() =
    runInputTest(focusWithMouse = false) { camera ->
      onRoot().performTouchInput {
        down(center)
        up()
        advanceEventTime(10)
        down(center)
        up()
      }
      mainClock.advanceTimeBy(1_000)
      waitForIdle()
      assertEquals(START_ZOOM, camera.position.zoom, ZOOM_TOLERANCE)
    }

  @Test
  fun a_bounce_does_not_reseed_the_double_tap_window() =
    runInputTest(focusWithMouse = false) { camera ->
      onRoot().performTouchInput {
        down(center)
        up()
        advanceEventTime(10)
        down(center)
        up()
        // Outside the original 300 ms window, but inside 300 ms of the bounce.
        advanceEventTime(300)
        down(center)
        up()
      }
      mainClock.advanceTimeBy(1_000)
      waitForIdle()
      assertEquals(START_ZOOM, camera.position.zoom, ZOOM_TOLERANCE)
    }

  @Test
  fun a_bounce_still_allows_a_later_tap_inside_the_original_window() =
    runInputTest(focusWithMouse = false) { camera ->
      onRoot().performTouchInput {
        down(center)
        up()
        advanceEventTime(10)
        down(center)
        up()
        advanceEventTime(70)
        down(center)
        up()
      }
      awaitZoom(camera, START_ZOOM + 1.0)
    }

  @Test
  fun a_touch_double_tap_may_land_inside_android_double_tap_slop() =
    runInputTest(focusWithMouse = false) { camera ->
      onRoot().performTouchInput {
        down(center)
        up()
        advanceEventTime(SECOND_TAP_GAP_MILLIS)
        down(center + Offset(50f, 0f))
        up()
      }
      awaitZoom(camera, START_ZOOM + 1.0)
    }

  @Test
  fun double_tap_drag_quick_zooms_the_map() =
    runInputTest(focusWithMouse = false) { camera ->
      onRoot().performTouchInput {
        click(center)
        advanceEventTime(SECOND_TAP_GAP_MILLIS)
        down(0, center)
        moveTo(0, center + Offset(0f, 100f), delayMillis = 100)
        up(0)
      }
      waitUntil(timeoutMillis = TIMEOUT) { camera.position.zoom > START_ZOOM + 0.25 }
    }

  @Test
  fun quick_zoom_does_not_leak_its_first_tap_as_a_map_click() =
    runInputTest(focusWithMouse = false) { camera ->
      onRoot().performTouchInput {
        click(center)
        advanceEventTime(SECOND_TAP_GAP_MILLIS)
        down(0, center)
        moveTo(0, center + Offset(0f, 100f), delayMillis = 100)
        up(0)
      }
      waitUntil(timeoutMillis = TIMEOUT) { camera.position.zoom > START_ZOOM + 0.25 }
      mainClock.advanceTimeBy(1_000)
      waitForIdle()
      assertEquals(0, clicks.size, "a quick zoom leaked its first tap as a map click")
    }

  @Test
  fun a_tap_waits_for_a_second_one_that_could_still_arrive() =
    runInputTest(focusWithMouse = false) {
      mainClock.autoAdvance = false
      try {
        onRoot().performTouchInput { click(center) }
        mainClock.advanceTimeByFrame()
        waitForIdle()
        assertEquals(0, clicks.size, "the tap reported before a double tap could rule it out")
      } finally {
        mainClock.autoAdvance = true
      }
      awaitClickCounts("the tap was not reported as a map click") { clicks.size == 1 }
    }

  @Test
  fun a_tap_reports_at_once_when_no_gesture_would_use_a_second_one() =
    runInputTest(
      gestures = GestureOptions(isDoubleClickZoomEnabled = false, isQuickZoomEnabled = false),
      focusWithMouse = false,
    ) {
      mainClock.autoAdvance = false
      try {
        onRoot().performTouchInput { click(center) }
        mainClock.advanceTimeByFrame()
        waitForIdle()
        assertEquals(1, clicks.size, "the tap waited for a double tap no gesture would use")
      } finally {
        mainClock.autoAdvance = true
      }
    }

  @Test
  fun a_second_tap_inside_the_bounce_window_still_clicks_when_no_gesture_awaits_it() =
    runInputTest(
      gestures = GestureOptions(isDoubleClickZoomEnabled = false, isQuickZoomEnabled = false),
      focusWithMouse = false,
    ) {
      onRoot().performTouchInput {
        down(center)
        up()
        advanceEventTime(10)
        down(center)
        up()
      }
      waitForIdle()
      assertEquals(2, clicks.size, "a bounce filter discarded a tap no gesture would pair")
    }

  @Test
  fun quick_zoom_upward_zooms_out() =
    runInputTest(focusWithMouse = false) { camera ->
      onRoot().performTouchInput {
        click(center)
        advanceEventTime(SECOND_TAP_GAP_MILLIS)
        down(0, center)
        moveTo(0, center - Offset(0f, 100f), delayMillis = 100)
        up(0)
      }
      waitUntil(timeoutMillis = TIMEOUT) { camera.position.zoom < START_ZOOM - 0.25 }
    }

  @Test
  fun horizontal_motion_disqualifies_quick_zoom_for_the_rest_of_the_press() =
    runInputTest(focusWithMouse = false) { camera ->
      val before = camera.position
      onRoot().performTouchInput {
        click(center)
        advanceEventTime(SECOND_TAP_GAP_MILLIS)
        down(0, center)
        moveTo(0, center + Offset(100f, 0f), delayMillis = 50)
        moveTo(0, center + Offset(100f, 100f), delayMillis = 50)
        up(0)
      }
      mainClock.advanceTimeBy(500)
      waitForIdle()

      assertEquals(before.zoom, camera.position.zoom, ZOOM_TOLERANCE)
      assertEquals(
        before.target.longitude,
        camera.position.target.longitude,
        TARGET_TOLERANCE,
        "a rejected quick zoom became a pan",
      )
    }

  @Test
  fun a_double_tap_drag_pans_when_quick_zoom_is_disabled() =
    runInputTest(
      gestures =
        GestureOptions(
          isQuickZoomEnabled = false,
          isFlingEnabled = false,
          isPinchZoomVelocityEnabled = false,
        ),
      focusWithMouse = false,
    ) { camera ->
      val before = camera.position
      onRoot().performTouchInput {
        click(center)
        advanceEventTime(SECOND_TAP_GAP_MILLIS)
        down(0, center)
        moveTo(0, center + Offset(80f, 0f), delayMillis = 100)
        up(0)
      }

      waitUntil(timeoutMillis = TIMEOUT) {
        camera.position.target.longitude != before.target.longitude
      }
      assertEquals(before.zoom, camera.position.zoom, ZOOM_TOLERANCE)
      awaitClickCounts("the tap was not reported as a map click") { clicks.size == 1 }
    }

  @Test
  fun quick_zoom_uses_the_resized_viewport() {
    val mapHeight = mutableStateOf(500.dp)
    val gestures = GestureOptions(isPinchZoomVelocityEnabled = false)

    runInputTest(
      gestures = gestures,
      focusWithMouse = false,
      mapModifier = { Modifier.fillMaxWidth().height(mapHeight.value).testTag(RESIZABLE_MAP_TAG) },
    ) { camera ->
      val map = onNodeWithTag(RESIZABLE_MAP_TAG)
      val initialHeight = map.fetchSemanticsNode().size.height
      // Start the pointer-input coroutine while the map still has its original dimensions.
      map.performMouseInput { click(center) }

      mapHeight.value = 250.dp
      waitUntil(timeoutMillis = TIMEOUT) { map.fetchSemanticsNode().size.height < initialHeight }
      val resizedHeight = map.fetchSemanticsNode().size.height
      val displacement = resizedHeight / 4f
      val before = camera.position

      map.performTouchInput {
        click(center)
        advanceEventTime(SECOND_TAP_GAP_MILLIS)
        down(0, center)
        moveTo(0, center + Offset(0f, displacement), delayMillis = 100)
        up(0)
      }

      val expectedZoom =
        before.zoom + displacement / resizedHeight * gestures.quickZoomMaxZoomChange
      waitUntil(timeoutMillis = TIMEOUT) {
        abs(camera.position.zoom - expectedZoom) < ZOOM_TOLERANCE
      }
      assertEquals(before.target.longitude, camera.position.target.longitude, 1e-5, "longitude")
      assertEquals(before.target.latitude, camera.position.target.latitude, 1e-5, "latitude")
    }
  }

  @Test
  fun pitched_fling_up_and_down_travel_similar_ground_distance() =
    runInputTest(focusWithMouse = false) { camera ->
      val start = CameraPosition(target = Position(0.0, 0.0), zoom = 8.0, tilt = 60.0)
      camera.position = start
      waitUntil(timeoutMillis = TIMEOUT) { abs(camera.position.tilt - 60.0) < 0.5 }

      val down = verticalFlingLatitudeDelta(camera, swipeY = 200f)
      camera.position = start
      waitUntil(timeoutMillis = TIMEOUT) {
        abs(camera.position.target.latitude) < 1e-4 && abs(camera.position.tilt - 60.0) < 0.5
      }
      val up = -verticalFlingLatitudeDelta(camera, swipeY = -200f)

      assertTrue(down > 0.0, "a downward flick should move the camera north, got $down")
      assertTrue(up > 0.0, "an upward flick should move the camera south, got $up")
      val ratio = maxOf(down, up) / minOf(down, up)
      assertTrue(
        ratio < 1.2,
        "pitched flings should travel similar ground distance; down=$down up=$up ratio=$ratio",
      )
    }

  /**
   * Swipes vertically and returns the signed latitude change after the fling settles. Downward
   * swipes are positive Y and move the camera north.
   */
  private fun androidx.compose.ui.test.ComposeUiTest.verticalFlingLatitudeDelta(
    camera: PresentationCamera,
    swipeY: Float,
  ): Double {
    val before = camera.position.target.latitude
    onRoot().performTouchInput { swipe(center, center + Offset(0f, swipeY), durationMillis = 100) }
    awaitReleasedTouchMomentum(camera)
    waitUntil(timeoutMillis = TIMEOUT) { !camera.isCameraMoving }
    return camera.position.target.latitude - before
  }

  @Test
  fun mouse_wheel_finishes_an_interrupted_touch_fling() =
    runInputTest(
      gestures = GestureOptions(animationDuration = Duration.ZERO),
      focusWithMouse = false,
    ) { camera ->
      val map = onRoot()
      val expectedZoom = START_ZOOM + GestureOptions.Standard.scrollZoomStep

      mainClock.autoAdvance = false
      try {
        map.performTouchInput {
          swipe(center - Offset(100f, 0f), center + Offset(100f, 0f), durationMillis = 100)
        }
        awaitReleasedTouchMomentum(camera)
        map.performMouseInput {
          moveTo(center)
          scroll(-1f)
        }
        mainClock.advanceTimeByFrame()
        waitForIdle()
        assertTrue(camera.isCameraMoving, "the wheel closed the gesture it took over")
        mainClock.advanceTimeBy(SCROLL_HOLD_MILLIS + FRAME_MILLIS)
        waitUntil(timeoutMillis = TIMEOUT) { !camera.isCameraMoving }
        mainClock.advanceTimeBy(2_000)
      } finally {
        mainClock.autoAdvance = true
      }

      waitUntil(timeoutMillis = TIMEOUT) {
        abs(camera.position.zoom - expectedZoom) < ZOOM_TOLERANCE
      }
      assertEquals(expectedZoom, camera.position.zoom, ZOOM_TOLERANCE)
    }

  @Test
  fun a_hover_does_not_cancel_a_touch_fling() =
    runInputTest(focusWithMouse = false) { camera ->
      val map = onRoot()
      mainClock.autoAdvance = false
      try {
        map.performTouchInput {
          swipe(center - Offset(100f, 0f), center + Offset(100f, 0f), durationMillis = 100)
        }
        awaitReleasedTouchMomentum(camera)

        map.performMouseInput { moveTo(center) }
        mainClock.advanceTimeByFrame()
        waitForIdle()
        assertTrue(camera.isCameraMoving, "a hover cancelled the fling")
      } finally {
        mainClock.autoAdvance = true
      }
    }

  @Test
  fun a_tap_finishes_an_interrupted_touch_fling() =
    runInputTest(focusWithMouse = false) { camera ->
      val map = onRoot()
      mainClock.autoAdvance = false
      try {
        map.performTouchInput {
          swipe(center - Offset(100f, 0f), center + Offset(100f, 0f), durationMillis = 100)
        }
        awaitReleasedTouchMomentum(camera)

        map.performTouchInput { click(center) }

        waitUntil(timeoutMillis = TIMEOUT) { !camera.isCameraMoving }
      } finally {
        mainClock.autoAdvance = true
      }
    }

  /**
   * Waits until the swipe's camera move is visible. The map reports that move from its owner
   * thread, which can land after Compose has gone idle. The clock stays frozen. The fling waits for
   * later frames, and the interruption under test still sees a moving camera.
   */
  private fun androidx.compose.ui.test.ComposeUiTest.awaitReleasedTouchMomentum(
    camera: PresentationCamera
  ) {
    mainClock.advanceTimeByFrame()
    waitUntil(timeoutMillis = TIMEOUT) { camera.isCameraMoving }
  }

  /** Waits for the camera to settle at [zoom]. */
  private fun androidx.compose.ui.test.ComposeUiTest.awaitZoom(
    camera: PresentationCamera,
    zoom: Double,
  ) {
    waitUntil(timeoutMillis = TIMEOUT) { abs(camera.position.zoom - zoom) < ZOOM_TOLERANCE }
  }

  @Test
  fun position_locked_zooms_without_moving_the_camera() =
    runInputTest(gestures = GestureOptions.PositionLocked) { camera ->
      val before = camera.position
      // Off centre, so an anchored zoom would visibly drag the target toward it.
      onRoot().performMouseInput { doubleClick(Offset(width * 0.2f, height * 0.2f)) }
      waitUntil(timeoutMillis = TIMEOUT) { camera.position.zoom > before.zoom }

      val after = camera.position
      assertEquals(before.target.longitude, after.target.longitude, TARGET_TOLERANCE, "longitude")
      assertEquals(before.target.latitude, after.target.latitude, TARGET_TOLERANCE, "latitude")
    }

  @Test
  fun position_locked_rotates_without_moving_the_camera() =
    runInputTest(
      gestures = GestureOptions.PositionLocked.copy(isRotateVelocityEnabled = false),
      focusWithMouse = false,
    ) { camera ->
      val before = camera.position
      val anchor = Offset(onRoot().fetchSemanticsNode().size.width * 0.3f, 240f)

      onRoot().performTouchInput {
        down(0, anchor - Offset(70f, 0f))
        down(1, anchor + Offset(70f, 0f))
        updatePointerTo(0, anchor - Offset(0f, 70f))
        updatePointerTo(1, anchor + Offset(0f, 70f))
        move(delayMillis = 20)
        up(0)
        up(1)
      }

      waitUntil(timeoutMillis = TIMEOUT) { camera.position.bearing != before.bearing }
      val after = camera.position
      assertEquals(before.target.longitude, after.target.longitude, TARGET_TOLERANCE, "longitude")
      assertEquals(before.target.latitude, after.target.latitude, TARGET_TOLERANCE, "latitude")
    }

  /** Composes a map, waits for it to render, focuses it with a click, then runs [body]. */
  private fun runInputTest(
    gestures: GestureOptions = GestureOptions.Standard,
    focusWithMouse: Boolean = true,
    mapModifier: @Composable () -> Modifier = { Modifier.fillMaxSize() },
    parentOnClick: (() -> Unit)? = null,
    parentOnLongClick: (() -> Unit)? = null,
    body: androidx.compose.ui.test.ComposeUiTest.(PresentationCamera) -> Unit,
  ) = runFfiComposeUiTest {
    val frames = AtomicInt(0)
    val initialPosition = CameraPosition(target = Position(0.0, 0.0), zoom = START_ZOOM)
    lateinit var mapState: MapState

    setFfiTestMapContent(runtimeOptions) {
      mapState =
        rememberMapState(
          initialCameraPosition = initialPosition,
          initialBaseStyle = BaseStyle.Empty,
        )
      val content: @Composable () -> Unit = {
        MaplibreMap(
          state = mapState,
          modifier = mapModifier(),
          presentationOptions = MapPresentationOptions(gestureOptions = gestures),
          callbacks =
            MapPresentationCallbacks(
              onClick = { position, _ ->
                clicks.add(position)
                ClickResult.Pass
              },
              onLongClick = { position, _ ->
                longClicks.add(position)
                ClickResult.Pass
              },
              onFrame = { frames.incrementAndFetch() },
            ),
        )
      }
      when {
        parentOnLongClick != null ->
          Box(
            Modifier.fillMaxSize().combinedClickable(onClick = {}, onLongClick = parentOnLongClick)
          ) {
            content()
          }
        parentOnClick != null ->
          Box(Modifier.fillMaxSize().clickable(onClick = parentOnClick)) { content() }
        else -> content()
      }
    }

    // The map thread reports the first viewport, and a suspended test body pumps no snapshot apply
    // notifications; waitUntil polls with frame pumps, so that report can't strand it.
    try {
      waitUntil(timeoutMillis = TIMEOUT) { mapState.presentation?.viewport != null }
    } catch (timeout: ComposeTimeoutException) {
      throw AssertionError(
        "the map never published a viewport within ${TIMEOUT}ms " +
          "(presentation=${mapState.presentation != null})",
        timeout,
      )
    }
    val camera = PresentationCamera(mapState)
    camera.position = initialPosition
    waitUntil(timeoutMillis = TIMEOUT) { frames.load() > 0 }
    waitUntil(timeoutMillis = TIMEOUT) {
      kotlin.math.abs(camera.position.zoom - START_ZOOM) < 0.001
    }

    // A click is what gives the map focus, so the keyboard cases depend on it too. Keep it outside
    // the double-click slop, or a test's first click becomes the second half of this one.
    if (focusWithMouse) onRoot().performMouseInput { click(Offset(10f, 10f)) }

    body(camera)
  }

  /** Keeps input-test assertions compact while routing every mutation through the live lease. */
  private class PresentationCamera(private val state: MapState) {
    private val presentation: MapPresentation
      get() = requireNotNull(state.presentation)

    var position: CameraPosition
      get() = state.cameraPosition
      set(value) {
        presentation.setCameraPosition(value)
      }

    val viewport
      get() = presentation.viewport

    val isCameraMoving
      get() = presentation.isCameraMoving

    val moveReason
      get() = presentation.cameraMoveReason
  }

  /**
   * [waitUntil] for the click counters. A timeout reports both counts, so a flaky run in CI shows
   * which report went missing instead of an anonymous timeout.
   */
  private fun ComposeUiTest.awaitClickCounts(description: String, condition: () -> Boolean) {
    try {
      waitUntil(timeoutMillis = TIMEOUT, condition = condition)
    } catch (timeout: ComposeTimeoutException) {
      throw AssertionError(
        "$description within ${TIMEOUT}ms (clicks=${clicks.size}, longClicks=${longClicks.size})",
        timeout,
      )
    }
  }

  private companion object {
    const val TIMEOUT = 30_000L
    const val START_ZOOM = 4.0

    /** The test clock advances a whole frame at a time, and never on its own under `waitUntil`. */
    const val FRAME_MILLIS = 16L

    /** Past Compose's 40 ms bounce filter and inside the 300 ms double-tap window. */
    const val SECOND_TAP_GAP_MILLIS = 80L

    val SCROLL_HOLD_MILLIS = GestureOptions.Standard.scrollZoomHold.inWholeMilliseconds

    const val RESIZABLE_MAP_TAG = "resizable-map"

    /** A zoom about the centre still drifts the target a hair through the projection. */
    const val TARGET_TOLERANCE = 1e-6

    const val ZOOM_TOLERANCE = 0.001
  }
}
