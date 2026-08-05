package org.maplibre.compose.map

import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import kotlin.time.Duration.Companion.milliseconds

/** How far a press may move, in logical pixels, and still count as a click rather than a drag. */
private const val CLICK_SLOP_PX = 4f

/** How far the camera moves per keyboard pan, in logical pixels. */
private const val KEYBOARD_PAN_STEP = 100.0

/** Zoom applied per keyboard step and per double click, as a scale multiplier. */
private const val KEYBOARD_ZOOM_STEP = 2.0

/**
 * How long a discrete input (arrow key, double click) takes to ease the camera, matching MapLibre
 * GL JS. A repeat supersedes the transition still in flight, so a held arrow key pans continuously.
 */
private val INPUT_ANIMATION_DURATION = 300.milliseconds

/**
 * Zoom exponent per unit of scroll, applied as `2^(-scroll * factor)`. Matches the
 * maplibre-native-ffi Compose example.
 */
private const val SCROLL_ZOOM_FACTOR = 0.25

/**
 * Wires Compose pointer and keyboard input to a [DesktopMapSession].
 *
 * MapLibre Native deliberately does not own platform gestures, so desktop input is implemented here
 * rather than forwarded. Pointer positions arrive as physical Compose pixels and are converted
 * once, here, to the logical pixels MapLibre projects in.
 */
internal fun Modifier.desktopMapInput(
  session: DesktopMapSession,
  options: GestureOptions,
  density: Density,
  focusRequester: FocusRequester,
): Modifier =
  this
    // Key events only reach a focused node; the map takes focus on press, so the keyboard works
    // only once the user has interacted with the map.
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
    .focusRequester(focusRequester)
    .focusable()
    .pointerInput(session, options, density) {
      // MapLibre keeps the gesture flag until it is cleared, so a drag ended by coroutine
      // cancellation rather than a pointer-up must clear it in the finally below.
      var dragging = false
      try {
        awaitPointerEventScope {
          // Tracks the previous pressed position rather than using awaitFirstDown, so that the
          // secondary button also starts a drag and rotation is re-evaluated on every event
          // (button and modifier can change mid-drag).
          var previous: PointerInputChange? = null
          // Cleared once the pointer travels past the click slop; null means "no longer a click".
          // Compared in logical pixels so the threshold means the same thing at any display scale.
          var pressOrigin: Offset? = null
          var pressWasSecondary = false
          var pressWasShifted = false
          // Compose has no click count on desktop, so double click is time plus distance. The
          // timeout is Compose's own, not the fixed 400ms the pre-rewrite implementation used.
          var lastClickAt: Long? = null
          var lastClickOrigin = Offset.Zero

          while (true) {
            val event = awaitPointerEvent()
            val current = event.changes.firstOrNull()?.takeIf { it.pressed }

            if (current == null) {
              if (dragging) {
                dragging = false
                session.onGestureEnded()
              } else {
                pressOrigin?.let { origin ->
                  val where = origin.toLogicalDpOffset(density)
                  if (pressWasSecondary) {
                    session.onSecondaryClick(where)
                  } else {
                    val now = event.changes.firstOrNull()?.uptimeMillis ?: 0L
                    val isDoubleClick =
                      lastClickAt?.let { previousAt ->
                        now - previousAt <= viewConfiguration.doubleTapTimeoutMillis &&
                          (origin - lastClickOrigin).getDistance() <=
                            CLICK_SLOP_PX * density.density
                      } == true

                    if (isDoubleClick && options.isDoubleClickZoomEnabled) {
                      // Anchored at the pointer so the point under the cursor stays put; shift
                      // inverts the direction, as web maps do.
                      session.scaleBy(
                        scale =
                          if (pressWasShifted) 1.0 / KEYBOARD_ZOOM_STEP else KEYBOARD_ZOOM_STEP,
                        anchor = options.zoomAnchor(where),
                        duration = INPUT_ANIMATION_DURATION,
                      )
                      // Cleared so a third click starts a new pair rather than zooming again.
                      lastClickAt = null
                    } else {
                      // The first click of a pair still reports; withholding it until the
                      // double-click timeout would make every click late.
                      session.onPrimaryClick(where)
                      lastClickAt = now
                      lastClickOrigin = origin
                    }
                  }
                }
              }
              pressOrigin = null
              previous = null
              continue
            }

            val last = previous
            previous = current
            if (last == null) {
              pressOrigin = current.position
              pressWasSecondary = event.buttons.isSecondaryPressed
              pressWasShifted = event.keyboardModifiers.isShiftPressed
              // Taking focus on press is what makes the keyboard handling above reachable.
              runCatching { focusRequester.requestFocus() }
              // A transition still in flight would keep animating against the pointer.
              session.cancelTransitions()
              continue
            }

            val delta = current.position - last.position
            if (delta == Offset.Zero) continue

            val fromOrigin = pressOrigin?.let { (current.position - it).getDistance() } ?: 0f
            if (fromOrigin > CLICK_SLOP_PX * density.density) pressOrigin = null

            if (!dragging) {
              dragging = true
              session.onGestureStarted()
            }

            val rotating = event.buttons.isSecondaryPressed || event.keyboardModifiers.isCtrlPressed
            applyDrag(session, options, density, current, delta, rotating)
          }
        }
      } finally {
        if (dragging) session.onGestureEnded()
      }
    }
    .pointerInput(session, options, density) {
      awaitEachGesture {
        // A separate loop because scroll arrives without a preceding press.
        while (true) {
          val event = awaitPointerEvent()
          if (event.type != PointerEventType.Scroll) continue
          if (!options.isScrollZoomEnabled) continue
          val change = event.changes.firstOrNull() ?: continue
          val scrollY = change.scrollDelta.y
          if (scrollY == 0f) continue

          // Anchored at the pointer so the point under the cursor stays put.
          session.scaleBy(
            scale = Math.pow(2.0, -scrollY.toDouble() * SCROLL_ZOOM_FACTOR),
            anchor = options.zoomAnchor(change.position.toLogicalDpOffset(density)),
          )
          change.consume()
        }
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
  session.moveBy(deltaX, deltaY, INPUT_ANIMATION_DURATION)
  return true
}

private fun zoom(session: DesktopMapSession, options: GestureOptions, scale: Double): Boolean {
  if (!options.isKeyboardZoomEnabled) return false
  session.scaleBy(scale, anchor = null, duration = INPUT_ANIMATION_DURATION)
  return true
}

/**
 * Where a pointer-driven zoom should pivot: the pointer, or the viewport centre. Anchoring at the
 * pointer moves the camera target, so when panning is disabled zoom pivots on the centre instead.
 */
private fun GestureOptions.zoomAnchor(pointer: DpOffset): DpOffset? =
  if (isDragPanEnabled) pointer else null

/** Converts a physical Compose position to the logical pixels MapLibre projects in. */
private fun Offset.toLogicalDpOffset(density: Density): DpOffset =
  DpOffset((x / density.density).dp, (y / density.density).dp)
