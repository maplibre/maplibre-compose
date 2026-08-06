package org.maplibre.compose.map

import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import kotlin.math.pow

/**
 * Wires Compose pointer and keyboard input to a [MlnFfiMapSession].
 *
 * MapLibre Native deliberately does not own platform gestures, so input is implemented here rather
 * than forwarded. Pointer positions arrive as physical Compose pixels and are converted once, here,
 * to the logical pixels MapLibre projects in.
 */
internal fun Modifier.mlnFfiMapInput(
  session: MlnFfiMapSession,
  options: GestureOptions,
  density: Density,
  focusRequester: FocusRequester,
): Modifier =
  this.keyboardInput(session, options)
    .focusRequester(focusRequester)
    .focusable()
    .pointerGestures(session, options, density, focusRequester)
    .scrollZoom(session, options, density)

/** Arrow keys pan, shift and an arrow key rotates or tilts, and `+` and `-` zoom. */
private fun Modifier.keyboardInput(session: MlnFfiMapSession, options: GestureOptions): Modifier =
  // Key events only reach a focused node; the map takes focus on press, so the keyboard works only
  // once the user has interacted with the map.
  onKeyEvent { event ->
    if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
    val shifted = event.isShiftPressed
    val panStep = options.keyboardPanStep.value.toDouble()
    when (event.key) {
      Key.DirectionLeft ->
        if (shifted) session.rotateAndTilt(options, bearingDelta = -options.keyboardRotateStep)
        else session.pan(options, panStep, 0.0)
      Key.DirectionRight ->
        if (shifted) session.rotateAndTilt(options, bearingDelta = options.keyboardRotateStep)
        else session.pan(options, -panStep, 0.0)
      Key.DirectionUp ->
        if (shifted) session.rotateAndTilt(options, pitchDelta = options.keyboardPitchStep)
        else session.pan(options, 0.0, panStep)
      Key.DirectionDown ->
        if (shifted) session.rotateAndTilt(options, pitchDelta = -options.keyboardPitchStep)
        else session.pan(options, 0.0, -panStep)
      Key.Plus,
      Key.Equals -> session.zoom(options, options.zoomStep)
      Key.Minus -> session.zoom(options, -options.zoomStep)
      else -> false
    }
  }

private fun Modifier.pointerGestures(
  session: MlnFfiMapSession,
  options: GestureOptions,
  density: Density,
  focusRequester: FocusRequester,
): Modifier =
  pointerInput(session, options, density) {
    val gesture =
      MapPointerGesture(
        session = session,
        options = options,
        density = density,
        focusRequester = focusRequester,
        clickSlopPx = options.clickSlop.toPx(),
        touchSlopPx = viewConfiguration.touchSlop,
        doubleClickTimeoutMillis = viewConfiguration.doubleTapTimeoutMillis,
      )
    try {
      awaitPointerEventScope { while (true) gesture.onPointerEvent(awaitPointerEvent()) }
    } finally {
      // MapLibre keeps the gesture flag until it is cleared, so a drag ended by coroutine
      // cancellation rather than by a pointer-up has to clear it here.
      gesture.endDrag()
    }
  }

private fun Modifier.scrollZoom(
  session: MlnFfiMapSession,
  options: GestureOptions,
  density: Density,
): Modifier =
  // Separate from the press loop because scroll arrives without a preceding press.
  pointerInput(session, options, density) {
    awaitEachGesture {
      while (true) {
        val event = awaitPointerEvent()
        if (event.type != PointerEventType.Scroll) continue
        if (!options.isScrollZoomEnabled) continue
        val change = event.changes.firstOrNull() ?: continue
        val scroll = change.scrollDelta.y
        if (scroll == 0f) continue

        // Anchored at the pointer so the point under it stays put.
        session.scaleBy(
          scale = zoomLevelsToScale(-scroll.toDouble() * options.scrollZoomStep),
          anchor = options.zoomAnchor(change.position.toLogicalDpOffset(density)),
        )
        change.consume()
      }
    }
  }

/**
 * Turns one pointer's event stream into map commands: press, drag, click, double click.
 *
 * Not Compose's own drag and tap detectors: a map needs a stream that becomes a pan, a rotate or a
 * click depending on the button and modifiers held as it moves, which they decide up front.
 */
private class MapPointerGesture(
  private val session: MlnFfiMapSession,
  private val options: GestureOptions,
  private val density: Density,
  private val focusRequester: FocusRequester,
  private val clickSlopPx: Float,
  private val touchSlopPx: Float,
  private val doubleClickTimeoutMillis: Long,
) {
  private var isDragging = false

  /** The last pressed position, or null when nothing is pressed. */
  private var lastPressed: PointerInputChange? = null

  /**
   * Where the press began, cleared once the drag starts; null means the press is no longer a
   * candidate click. Held in physical pixels, as Compose reports them.
   */
  private var clickOrigin: Offset? = null
  private var pressedSecondary = false
  private var pressedShifted = false
  private var pressedType = PointerType.Mouse

  private var lastClickAt: Long? = null
  private var lastClickOrigin = Offset.Zero

  fun onPointerEvent(event: PointerEvent) {
    val change = event.changes.firstOrNull()?.takeIf { it.pressed }
    when {
      change == null -> onRelease(event)
      lastPressed == null -> onPress(event, change)
      else -> onDrag(event, change)
    }
  }

  private fun onPress(event: PointerEvent, change: PointerInputChange) {
    lastPressed = change
    clickOrigin = change.position
    pressedSecondary = event.buttons.isSecondaryPressed
    pressedShifted = event.keyboardModifiers.isShiftPressed
    pressedType = change.type
    // Taking focus on press is what makes the keyboard handling reachable.
    runCatching { focusRequester.requestFocus() }
    // A transition still in flight would keep animating against the pointer.
    session.cancelTransitions()
  }

  private fun onDrag(event: PointerEvent, change: PointerInputChange) {
    val delta = change.position - (lastPressed ?: return).position
    lastPressed = change
    if (delta == Offset.Zero) return

    if (!isDragging) {
      // Under the slop the press is still a click, so that jitter during one does not lose it.
      val origin = clickOrigin
      if (origin != null && (change.position - origin).getDistance() <= slopPx()) return
      clickOrigin = null
      isDragging = true
      session.onGestureStarted()
    }

    // Read every event rather than at press, so that releasing ctrl mid-drag switches to panning.
    val rotating = event.buttons.isSecondaryPressed || event.keyboardModifiers.isCtrlPressed
    val deltaX = (delta.x / density.density).toDouble()
    val deltaY = (delta.y / density.density).toDouble()

    if (rotating) {
      if (!options.isDragRotateTiltEnabled) return
      session.rotateAndPitchBy(
        bearingDelta = deltaX * options.dragRotateDegreesPerDp,
        pitchDelta = deltaY * options.dragPitchDegreesPerDp,
      )
    } else {
      if (!options.isDragPanEnabled) return
      session.moveBy(deltaX, deltaY)
    }
    change.consume()
  }

  /** A pointer-up ends a drag, or completes a click if the press never travelled past the slop. */
  private fun onRelease(event: PointerEvent) {
    val origin = clickOrigin
    lastPressed = null
    clickOrigin = null

    if (isDragging) endDrag()
    else if (origin != null) onClick(origin, event.changes.firstOrNull()?.uptimeMillis ?: 0L)
  }

  private fun onClick(origin: Offset, timeMillis: Long) {
    val where = origin.toLogicalDpOffset(density)
    if (pressedSecondary) return session.onSecondaryClick(where)

    if (isDoubleClick(origin, timeMillis) && options.isDoubleClickZoomEnabled) {
      // Anchored at the pointer so the point under it stays put; shift inverts the direction.
      session.scaleBy(
        scale = zoomLevelsToScale(if (pressedShifted) -options.zoomStep else options.zoomStep),
        anchor = options.zoomAnchor(where),
        duration = options.animationDuration,
      )
      // Cleared so a third click starts a new pair rather than zooming again.
      lastClickAt = null
    } else {
      // The first click of a pair still reports; withholding it until the double-click timeout
      // would make every click late.
      session.onPrimaryClick(where)
      lastClickAt = timeMillis
      lastClickOrigin = origin
    }
  }

  /** Compose reports no click count on desktop, so a double click is a time plus a distance. */
  private fun isDoubleClick(origin: Offset, timeMillis: Long): Boolean {
    val previousAt = lastClickAt ?: return false
    return timeMillis - previousAt <= doubleClickTimeoutMillis &&
      (origin - lastClickOrigin).getDistance() <= slopPx()
  }

  /**
   * A finger needs far more room than a mouse, so the pressed pointer decides which slop applies.
   */
  private fun slopPx(): Float = if (pressedType == PointerType.Mouse) clickSlopPx else touchSlopPx

  /** Ends the drag if one is running; both a pointer-up and a cancellation route here. */
  fun endDrag() {
    if (!isDragging) return
    isDragging = false
    session.onGestureEnded()
  }
}

private fun MlnFfiMapSession.pan(options: GestureOptions, deltaX: Double, deltaY: Double): Boolean {
  if (!options.isKeyboardPanEnabled) return false
  moveBy(deltaX, deltaY, options.animationDuration)
  return true
}

private fun MlnFfiMapSession.zoom(options: GestureOptions, levelDelta: Double): Boolean {
  if (!options.isKeyboardZoomEnabled) return false
  scaleBy(zoomLevelsToScale(levelDelta), anchor = null, duration = options.animationDuration)
  return true
}

private fun MlnFfiMapSession.rotateAndTilt(
  options: GestureOptions,
  bearingDelta: Double = 0.0,
  pitchDelta: Double = 0.0,
): Boolean {
  if (!options.isKeyboardRotateTiltEnabled) return false
  rotateAndPitchBy(bearingDelta, pitchDelta, options.animationDuration)
  return true
}

/** A zoom level delta as the scale multiplier the session takes; a level is a doubling. */
private fun zoomLevelsToScale(levelDelta: Double): Double = 2.0.pow(levelDelta)

/**
 * Where a pointer-driven zoom should pivot: the pointer, or the viewport centre. Anchoring at the
 * pointer moves the camera target, so when panning is disabled zoom pivots on the centre instead.
 */
private fun GestureOptions.zoomAnchor(pointer: DpOffset): DpOffset? =
  if (isDragPanEnabled) pointer else null

/** Converts a physical Compose position to the logical pixels MapLibre projects in. */
private fun Offset.toLogicalDpOffset(density: Density): DpOffset =
  DpOffset((x / density.density).dp, (y / density.density).dp)
