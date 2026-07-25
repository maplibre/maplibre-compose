package org.maplibre.compose.map

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp

/** How far the camera moves per keyboard pan, in logical pixels. */
private const val KEYBOARD_PAN_STEP = 100.0

/** Zoom applied per keyboard step and per double click, as a scale multiplier. */
private const val KEYBOARD_ZOOM_STEP = 2.0

/**
 * Zoom exponent per unit of scroll, applied as `2^(-scroll * factor)`.
 *
 * Matches the maplibre-native-ffi Compose example; a plain multiplier per notch feels wrong because
 * zoom is logarithmic.
 */
private const val SCROLL_ZOOM_FACTOR = 0.25

/**
 * Wires Compose pointer and keyboard input to a [DesktopMapSession].
 *
 * MapLibre Native deliberately does not own platform gestures, so desktop input is implemented here
 * rather than forwarded. Keeping it independent of the host factory means AWT, compose-glfw, and
 * any future host behave identically.
 *
 * Pointer positions arrive as physical Compose pixels and are converted once, here, to the logical
 * pixels MapLibre projects in. Converting again further down would double-apply the display scale.
 */
internal fun Modifier.desktopMapInput(
  session: DesktopMapSession,
  options: GestureOptions,
  density: Density,
): Modifier =
  this.pointerInput(session, options, density) {
      awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        // A drag takes over from any transition still in flight; without this the animation keeps
        // fighting the pointer.
        session.cancelTransitions()
        var totalDrag = Offset.Zero
        var isDragging = false

        // Secondary button, or control with the primary button, rotates and pitches instead of
        // panning — the convention the mobile SDKs use for two-finger drag.
        val rotating = down.let {
          val event = currentEvent
          event.buttons.isSecondaryPressed || event.keyboardModifiers.isCtrlPressed
        }

        while (true) {
          val event = awaitPointerEvent()
          val change = event.changes.firstOrNull { it.id == down.id } ?: break
          if (!change.pressed) break

          val delta = change.positionChange()
          if (delta != Offset.Zero) {
            if (!isDragging) {
              isDragging = true
              session.onGestureStarted()
            }
            totalDrag += delta
            applyDrag(session, options, density, change, delta, rotating)
          }
          if (event.type == PointerEventType.Release) break
        }

        if (isDragging) session.onGestureEnded()
      }
    }
    .pointerInput(session, options, density) {
      awaitEachGesture {
        // Scroll is a separate gesture loop: it arrives without a preceding press, so it cannot be
        // folded into the drag loop above.
        while (true) {
          val event = awaitPointerEvent()
          if (event.type != PointerEventType.Scroll) continue
          if (!options.isScrollZoomEnabled) continue
          val change = event.changes.firstOrNull() ?: continue
          val scrollY = change.scrollDelta.y
          if (scrollY == 0f) continue

          // Anchored at the pointer so the point under the cursor stays put, which is what makes
          // scroll zoom feel attached to the map rather than to the viewport.
          session.scaleBy(
            scale = Math.pow(2.0, -scrollY.toDouble() * SCROLL_ZOOM_FACTOR),
            anchor = change.position.toLogicalDpOffset(density),
          )
          change.consume()
        }
      }
    }
    .onKeyEvent { event ->
      if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
      when (event.key) {
        Key.DirectionLeft -> pan(session, options, KEYBOARD_PAN_STEP, 0.0)
        Key.DirectionRight -> pan(session, options, -KEYBOARD_PAN_STEP, 0.0)
        Key.DirectionUp -> pan(session, options, 0.0, KEYBOARD_PAN_STEP)
        Key.DirectionDown -> pan(session, options, 0.0, -KEYBOARD_PAN_STEP)
        Key.Plus,
        Key.Equals -> zoom(session, options, KEYBOARD_ZOOM_STEP)
        Key.Minus -> zoom(session, options, 1.0 / KEYBOARD_ZOOM_STEP)
        else -> false
      }
    }

private fun applyDrag(
  session: DesktopMapSession,
  options: GestureOptions,
  density: Density,
  change: PointerInputChange,
  delta: Offset,
  rotating: Boolean,
) {
  if (rotating) {
    if (!options.isDragRotateTiltEnabled) return
    // One call, so bearing and pitch move together from a single drag.
    session.rotateAndPitchBy(
      deltaX = (delta.x / density.density).toDouble(),
      deltaY = (delta.y / density.density).toDouble(),
    )
  } else {
    if (!options.isDragPanEnabled) return
    session.moveBy(
      deltaX = (delta.x / density.density).toDouble(),
      deltaY = (delta.y / density.density).toDouble(),
    )
  }
  change.consume()
}

private fun pan(
  session: DesktopMapSession,
  options: GestureOptions,
  deltaX: Double,
  deltaY: Double,
): Boolean {
  if (!options.isKeyboardPanEnabled) return false
  session.moveBy(deltaX, deltaY)
  return true
}

private fun zoom(session: DesktopMapSession, options: GestureOptions, scale: Double): Boolean {
  if (!options.isKeyboardZoomEnabled) return false
  session.scaleBy(scale, anchor = null)
  return true
}

/** Converts a physical Compose position to the logical pixels MapLibre projects in. */
private fun Offset.toLogicalDpOffset(density: Density): DpOffset =
  DpOffset((x / density.density).dp, (y / density.density).dp)
