package org.maplibre.compose.map

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
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
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.CameraState
import org.maplibre.compose.camera.rememberCameraState
import org.maplibre.compose.mlnffi.FfiTestPlatform
import org.maplibre.compose.mlnffi.MlnFfiRuntimeOptions
import org.maplibre.compose.mlnffi.runFfiComposeUiTest
import org.maplibre.compose.mlnffi.setFfiTestMapContent
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.util.ClickResult
import org.maplibre.spatialk.geojson.Position

/** Keyboard and pointer input reach the map, and a click stays distinct from a drag. */
@OptIn(ExperimentalTestApi::class)
class MlnFfiMapInputTest {

  private val cachePath = FfiTestPlatform.createCachePath()

  /** Every click the map reported, including the one [runInputTest] uses to take focus. */
  private val clicks = mutableListOf<Position>()

  private val runtimeOptions =
    MlnFfiRuntimeOptions(cachePath = cachePath, maximumCacheSizeBytes = null)

  @AfterTest
  fun cleanUp() {
    FfiTestPlatform.deleteCachePath(cachePath)
  }

  @Test
  fun arrow_keys_pan_the_map() = runInputTest { camera ->
    val before = camera.position.target.longitude
    onRoot().performKeyInput { pressKey(Key.DirectionRight) }
    waitUntil(timeoutMillis = TIMEOUT) { camera.position.target.longitude != before }
  }

  @Test
  fun plus_and_minus_zoom_the_map() = runInputTest { camera ->
    onRoot().performKeyInput { pressKey(Key.Equals) }
    // The zoom has to arrive, not merely start: a transition only advances while frames render.
    awaitZoom(camera, START_ZOOM + 1.0)

    onRoot().performKeyInput { pressKey(Key.Minus) }
    awaitZoom(camera, START_ZOOM)
  }

  @Test
  fun double_click_zooms_in() = runInputTest { camera ->
    onRoot().performMouseInput { doubleClick() }
    awaitZoom(camera, START_ZOOM + 1.0)
  }

  @Test
  fun double_click_eases_rather_than_jumping() = runInputTest { camera ->
    val target = START_ZOOM + 1.0
    var sawIntermediate = false

    onRoot().performMouseInput { doubleClick() }
    waitUntil(timeoutMillis = TIMEOUT) {
      val zoom = camera.position.zoom
      if (zoom > START_ZOOM + 0.01 && zoom < target - 0.01) sawIntermediate = true
      zoom >= target - ZOOM_TOLERANCE
    }

    assertTrue(sawIntermediate, "the zoom went straight to $target, so it did not animate")
  }

  @Test
  fun mouse_wheel_zooms_at_the_pointer() = runInputTest { camera ->
    onRoot().performMouseInput {
      moveTo(Offset(width * 0.25f, height * 0.25f))
      scroll(-1f)
    }
    awaitZoom(camera, START_ZOOM + 1.0)
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

  /**
   * A press that moves a hair is still a click. The slop only earns its keep if it gates the start
   * of the drag: gating anything later leaves a press that jittered by a pixel dragging the map and
   * reporting nothing.
   */
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

    waitUntil(timeoutMillis = TIMEOUT) { clicks.size > clicksBefore }
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
      waitUntil(timeoutMillis = TIMEOUT) { clicks.size == clicksBefore + 1 }
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
      waitUntil(timeoutMillis = TIMEOUT) { clicks.size == clicksBefore + 2 }
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
        // Shove must still win once eligible instead of being permanently stolen by pan.
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
  fun two_finger_tap_zooms_out() =
    runInputTest(focusWithMouse = false) { camera ->
      onRoot().performTouchInput {
        down(0, center - Offset(40f, 0f))
        down(1, center + Offset(40f, 0f))
        up(0)
        up(1)
      }
      awaitZoom(camera, START_ZOOM - 1.0)
    }

  @Test
  fun double_tap_zooms_the_map() =
    runInputTest(focusWithMouse = false) { camera ->
      onRoot().performTouchInput { doubleClick() }
      awaitZoom(camera, START_ZOOM + 1.0)
      mainClock.advanceTimeBy(1_000)
      waitForIdle()
      assertEquals(0, clicks.size, "a double tap leaked its first tap as a map click")
    }

  @Test
  fun double_tap_drag_quick_zooms_the_map() =
    runInputTest(focusWithMouse = false) { camera ->
      onRoot().performTouchInput {
        click(center)
        down(0, center)
        moveTo(0, center + Offset(0f, 100f), delayMillis = 100)
        up(0)
      }
      waitUntil(timeoutMillis = TIMEOUT) { camera.position.zoom > START_ZOOM + 0.25 }
    }

  @Test
  fun quick_zoom_upward_zooms_out_like_classic_android() =
    runInputTest(focusWithMouse = false) { camera ->
      onRoot().performTouchInput {
        click(center)
        down(0, center)
        moveTo(0, center - Offset(0f, 100f), delayMillis = 100)
        up(0)
      }
      waitUntil(timeoutMillis = TIMEOUT) { camera.position.zoom < START_ZOOM - 0.25 }
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
  fun mouse_wheel_cancels_touch_zoom_momentum() =
    runInputTest(
      gestures = GestureOptions(animationDuration = Duration.ZERO),
      focusWithMouse = false,
    ) { camera ->
      val map = onRoot()
      val viewportHeight = map.fetchSemanticsNode().size.height
      val displacement = viewportHeight / 4f
      val expectedZoom = START_ZOOM + displacement / viewportHeight * 4.0 + 1.0

      mainClock.autoAdvance = false
      try {
        map.performTouchInput {
          click(center)
          down(0, center)
          moveTo(0, center + Offset(0f, displacement), delayMillis = 100)
          up(0)
        }
        map.performMouseInput {
          moveTo(center)
          scroll(-1f)
        }
        mainClock.advanceTimeBy(2_000)
      } finally {
        mainClock.autoAdvance = true
      }

      waitUntil(timeoutMillis = TIMEOUT) {
        abs(camera.position.zoom - expectedZoom) < ZOOM_TOLERANCE
      }
      assertEquals(expectedZoom, camera.position.zoom, ZOOM_TOLERANCE)
    }

  /** Waits for the camera to settle at [zoom], failing with the value it stopped at. */
  private fun androidx.compose.ui.test.ComposeUiTest.awaitZoom(camera: CameraState, zoom: Double) {
    waitUntil(timeoutMillis = TIMEOUT) { abs(camera.position.zoom - zoom) < ZOOM_TOLERANCE }
  }

  /** `PositionLocked` must still zoom, but without the pointer anchoring that would pan. */
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
    body: androidx.compose.ui.test.ComposeUiTest.(CameraState) -> Unit,
  ) = runFfiComposeUiTest {
    val frames = AtomicInteger()
    lateinit var cameraState: CameraState

    setFfiTestMapContent(runtimeOptions) {
      cameraState =
        rememberCameraState(
          firstPosition = CameraPosition(target = Position(0.0, 0.0), zoom = START_ZOOM)
        )
      MaplibreMap(
        modifier = mapModifier(),
        baseStyle = BaseStyle.Empty,
        cameraState = cameraState,
        options = MapOptions(gestureOptions = gestures),
        onMapClick = { position, _ ->
          clicks.add(position)
          ClickResult.Pass
        },
        onFrame = { frames.incrementAndGet() },
        logger = Logger.withTag("input-test"),
      )
    }

    waitUntil(timeoutMillis = TIMEOUT) { frames.get() > 0 }
    waitUntil(timeoutMillis = TIMEOUT) {
      kotlin.math.abs(cameraState.position.zoom - START_ZOOM) < 0.001
    }

    // A click is what gives the map focus, so the keyboard cases depend on it too.
    // Keep the focus click outside the test gestures' double-click slop; otherwise a fast test can
    // accidentally turn its first click into the second half of the focus click.
    if (focusWithMouse) onRoot().performMouseInput { click(Offset(10f, 10f)) }

    body(cameraState)
  }

  private companion object {
    const val TIMEOUT = 30_000L
    const val START_ZOOM = 4.0
    const val RESIZABLE_MAP_TAG = "resizable-map"

    /** A zoom about the centre still drifts the target a hair through the projection. */
    const val TARGET_TOLERANCE = 1e-6

    const val ZOOM_TOLERANCE = 0.001
  }
}
