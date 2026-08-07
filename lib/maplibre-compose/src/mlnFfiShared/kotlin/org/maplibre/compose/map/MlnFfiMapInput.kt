package org.maplibre.compose.map

import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.runtime.withFrameNanos
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
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.pow
import kotlin.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
  touchMomentum: TouchMomentum,
): Modifier =
  this.keyboardInput(session, options, touchMomentum)
    .focusRequester(focusRequester)
    .focusable()
    .pointerGestures(session, options, density, focusRequester, touchMomentum)
    .scrollZoom(session, options, density, touchMomentum)

/** Arrow keys pan, shift and an arrow key rotates or tilts, and `+` and `-` zoom. */
private fun Modifier.keyboardInput(
  session: MlnFfiMapSession,
  options: GestureOptions,
  touchMomentum: TouchMomentum,
): Modifier =
  // Key events only reach a focused node; the map takes focus on press, so the keyboard works only
  // once the user has interacted with the map.
  onKeyEvent { event ->
    if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
    val shifted = event.isShiftPressed
    val panStep = options.keyboardPanStep.value.toDouble()
    when (event.key) {
      Key.DirectionLeft ->
        if (shifted)
          session.rotateAndTilt(options, touchMomentum, bearingDelta = -options.keyboardRotateStep)
        else session.pan(options, touchMomentum, panStep, 0.0)
      Key.DirectionRight ->
        if (shifted)
          session.rotateAndTilt(options, touchMomentum, bearingDelta = options.keyboardRotateStep)
        else session.pan(options, touchMomentum, -panStep, 0.0)
      Key.DirectionUp ->
        if (shifted)
          session.rotateAndTilt(options, touchMomentum, pitchDelta = options.keyboardPitchStep)
        else session.pan(options, touchMomentum, 0.0, panStep)
      Key.DirectionDown ->
        if (shifted)
          session.rotateAndTilt(options, touchMomentum, pitchDelta = -options.keyboardPitchStep)
        else session.pan(options, touchMomentum, 0.0, -panStep)
      Key.Plus,
      Key.Equals -> session.zoom(options, touchMomentum, options.zoomStep)
      Key.Minus -> session.zoom(options, touchMomentum, -options.zoomStep)
      else -> false
    }
  }

private fun Modifier.pointerGestures(
  session: MlnFfiMapSession,
  options: GestureOptions,
  density: Density,
  focusRequester: FocusRequester,
  touchMomentum: TouchMomentum,
): Modifier =
  pointerInput(session, options, density, touchMomentum) {
    val scope = CoroutineScope(currentCoroutineContext())
    val gesture =
      MapPointerGesture(
        session = session,
        options = options,
        density = density,
        focusRequester = focusRequester,
        viewportSize = { size },
        clickSlopPx = options.clickSlop.toPx(),
        panSlopPx = ClassicAndroidGestureMath.PAN_START_DP.dp.toPx(),
        scaleSlopPx = ClassicAndroidGestureMath.SCALE_START_SPAN_DP.dp.toPx(),
        shoveSlopPx = ClassicAndroidGestureMath.SHOVE_START_DP.dp.toPx(),
        twoFingerTapSlopPx = ClassicAndroidGestureMath.TWO_FINGER_TAP_SLOP_DP.dp.toPx(),
        minimumTwoFingerSpanPx = ClassicAndroidGestureMath.MINIMUM_TWO_FINGER_SPAN_DP.dp.toPx(),
        doubleClickTimeoutMillis = viewConfiguration.doubleTapTimeoutMillis,
        longClickTimeoutMillis = viewConfiguration.longPressTimeoutMillis,
        scope = scope,
        touchMomentum = touchMomentum,
      )
    try {
      awaitPointerEventScope { while (true) gesture.onPointerEvent(awaitPointerEvent()) }
    } finally {
      // MapLibre keeps the gesture flag until it is cleared, so a drag ended by coroutine
      // cancellation rather than by a pointer-up has to clear it here.
      gesture.cancel()
    }
  }

private fun Modifier.scrollZoom(
  session: MlnFfiMapSession,
  options: GestureOptions,
  density: Density,
  touchMomentum: TouchMomentum,
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
        touchMomentum.cancel()
        session.cancelTransitions()
        session.scaleBy(
          scale = zoomLevelsToScale(-scroll.toDouble() * options.scrollZoomStep),
          anchor = options.zoomAnchor(change.position.toLogicalDpOffset(density)),
        )
        change.consume()
      }
    }
  }

/**
 * Turns pointer streams into mouse and touch map commands.
 *
 * Not Compose's own drag and tap detectors: a map needs a stream that becomes a pan, a rotate or a
 * click depending on the button and modifiers held as it moves, which they decide up front.
 */
private class MapPointerGesture(
  private val session: MlnFfiMapSession,
  private val options: GestureOptions,
  private val density: Density,
  private val focusRequester: FocusRequester,
  private val viewportSize: () -> IntSize,
  private val clickSlopPx: Float,
  private val panSlopPx: Float,
  private val scaleSlopPx: Float,
  private val shoveSlopPx: Float,
  private val twoFingerTapSlopPx: Float,
  private val minimumTwoFingerSpanPx: Float,
  private val doubleClickTimeoutMillis: Long,
  private val longClickTimeoutMillis: Long,
  private val scope: CoroutineScope,
  private val touchMomentum: TouchMomentum,
) {
  private var gestureInProgress = false
  private var mode = Mode.NONE

  /** The last sample of the one-pointer phase, or null outside one. */
  private var lastSingle: PointerInputChange? = null
  private var singleDragOrigin: Offset? = null
  private var singleMotion = SingleMotion.NONE
  private val singleVelocity = VelocityTracker()

  /**
   * Separate start and previous samples let pan run while scale/rotate/shove arbitration continues.
   */
  private var twoFingerStart: TwoFingerSample? = null
  private var twoFingerPrevious: TwoFingerSample? = null
  private var twoFingerPanning = false
  private var scaleAlongsideRotation = false
  private val twoFingerVelocity = VelocityTracker()
  private var lastSpanDeltaPixels = 0.0
  private var lastScaleWasOut = false
  private var lastTwoFingerAnchor: DpOffset? = null
  private var lastTwoFingerCentroidPixels = Offset.Zero
  private var lastClassicRotationDegrees = 0.0
  private var twoFingerTap: TwoFingerTapCandidate? = null
  private var deferredTwoFingerVelocity: (() -> Duration?)? = null
  private var gestureEndJob: Job? = null

  /**
   * Where the press began, cleared once the drag starts; null means the press is no longer a
   * candidate click. Held in physical pixels, as Compose reports them.
   */
  private var clickOrigin: Offset? = null
  private var pressedSecondary = false
  private var pressedShifted = false
  private var pressedType = PointerType.Mouse
  private var pressStartedAtMillis = 0L
  private var quickZoomCandidate = false
  private var quickZoomOriginY = 0f
  private var quickZoomAppliedDelta = 0.0
  private var lastQuickZoomSpanDeltaPixels = 0.0
  private var longClickJob: Job? = null

  private var lastClickAt: Long? = null
  private var lastClickOrigin = Offset.Zero
  private var lastClickType = PointerType.Mouse
  private var pendingTouchClick: PendingTouchClick? = null

  fun onPointerEvent(event: PointerEvent) {
    val pressed = event.changes.filter { it.pressed }
    updateTwoFingerTap(event, pressed.size)
    when {
      pressed.size >= 2 -> onTwoFinger(event, pressed[0], pressed[1])
      pressed.size == 1 -> onSingle(event, pressed.single())
      else -> onRelease(event)
    }
  }

  private fun onSingle(event: PointerEvent, change: PointerInputChange) {
    if (mode.isTwoFinger) {
      // A pinch may end one finger at a time. Continue smoothly with the remaining finger and keep
      // the gesture bracket open until it too leaves.
      // Android computes this velocity when the first pointer lifts, but starts the animator only
      // on the final ACTION_UP so the remaining pointer can still cancel it.
      deferredTwoFingerVelocity = prepareTwoFingerVelocity()
      mode = Mode.SINGLE
      twoFingerStart = null
      twoFingerPrevious = null
      twoFingerPanning = false
      scaleAlongsideRotation = false
      lastSingle = change
      singleDragOrigin = change.position
      singleMotion = SingleMotion.NONE
      singleVelocity.resetTracking()
      singleVelocity.addPointerInputChange(change)
      return
    }

    if (lastSingle == null) onPress(event, change) else onSingleDrag(event, change)
  }

  private fun onPress(event: PointerEvent, change: PointerInputChange) {
    mode = Mode.SINGLE
    lastSingle = change
    singleDragOrigin = change.position
    clickOrigin = change.position
    pressedSecondary = event.buttons.isSecondaryPressed
    pressedShifted = event.keyboardModifiers.isShiftPressed
    pressedType = change.type
    pressStartedAtMillis = change.uptimeMillis
    quickZoomCandidate =
      change.type != PointerType.Mouse && isDoubleClick(change.position, change.uptimeMillis)
    quickZoomOriginY = change.position.y
    quickZoomAppliedDelta = 0.0
    lastQuickZoomSpanDeltaPixels = 0.0
    singleMotion = SingleMotion.NONE
    singleVelocity.resetTracking()
    singleVelocity.addPointerInputChange(change)
    if (quickZoomCandidate) cancelPendingTouchClick()
    deferredTwoFingerVelocity = null
    cancelMomentumForNewGesture()
    // Taking focus on press is what makes the keyboard handling reachable.
    runCatching { focusRequester.requestFocus() }
    // A transition still in flight would keep animating against the pointer.
    session.cancelTransitions()

    if (change.type != PointerType.Mouse && !quickZoomCandidate) {
      val origin = change.position
      longClickJob = scope.launch {
        delay(longClickTimeoutMillis)
        if (clickOrigin == origin && !gestureInProgress && mode == Mode.SINGLE) {
          clickOrigin = null
          session.onSecondaryClick(origin.toLogicalDpOffset(density))
        }
      }
    }
  }

  private fun onSingleDrag(event: PointerEvent, change: PointerInputChange) {
    val previous = lastSingle ?: return
    val delta = change.position - previous.position
    lastSingle = change
    if (delta == Offset.Zero) return
    // A double-tap hold belongs exclusively to quick zoom, while mouse modifiers choose between
    // the desktop-style rotate/tilt and pan paths on every event.
    val rotating = event.buttons.isSecondaryPressed || event.keyboardModifiers.isCtrlPressed
    val canTransform =
      when {
        quickZoomCandidate -> options.isQuickZoomEnabled
        rotating -> options.isDragRotateTiltEnabled
        else -> options.isDragPanEnabled
      }

    if (!gestureInProgress) {
      // Under the slop the press is still a click, so that jitter during one does not lose it.
      val origin = singleDragOrigin
      val displacement = if (origin == null) Offset.Zero else change.position - origin
      if (quickZoomCandidate) {
        // The Android quick-scale detector doubles vertical displacement into its span. Horizontal
        // travel only disqualifies the double tap; it must never turn into a pan.
        if (abs(displacement.y) * 2f < scaleSlopPx) {
          if (abs(displacement.x) <= scaleSlopPx) return
          clickOrigin = null
          lastClickAt = null
          cancelPendingTouchClick()
          return
        }
      } else if (abs(displacement.x) < dragSlopPx() && abs(displacement.y) < dragSlopPx()) {
        return
      }
      clickOrigin = null
      if (!canTransform) {
        lastClickAt = null
        cancelPendingTouchClick()
        return
      }
      twoFingerTap = null
      beginGesture()
    }

    val deltaX = (delta.x / density.density).toDouble()
    val deltaY = (delta.y / density.density).toDouble()
    var changed = false

    if (quickZoomCandidate && options.isQuickZoomEnabled) {
      mode = Mode.QUICK_ZOOM
      singleMotion = SingleMotion.QUICK_ZOOM
      // The second tap now belongs to this drag; a later tap must start a fresh pair.
      lastClickAt = null
      val currentViewportSize = viewportSize()
      val targetDelta =
        ClassicAndroidGestureMath.quickZoomDelta(
          displacementPixels = (change.position.y - quickZoomOriginY).toDouble(),
          viewportHeightPixels = currentViewportSize.height.toDouble(),
          maximumZoomChange = options.quickZoomMaxZoomChange,
        )
      session.scaleBy(
        scale = zoomLevelsToScale(targetDelta - quickZoomAppliedDelta),
        // Android quick zoom is deliberately centred rather than finger anchored.
        anchor = viewportCenter(currentViewportSize),
      )
      lastQuickZoomSpanDeltaPixels = abs(delta.y) * 2.0
      quickZoomAppliedDelta = targetDelta
      changed = true
    } else {
      // Read every mouse event rather than only the press, so releasing ctrl switches to panning.
      if (rotating && options.isDragRotateTiltEnabled) {
        if (singleMotion != SingleMotion.ROTATE_TILT) singleVelocity.resetTracking()
        singleMotion = SingleMotion.ROTATE_TILT
        session.rotateAndPitchBy(
          bearingDelta = deltaX * options.dragRotateDegreesPerDp,
          pitchDelta = deltaY * options.dragPitchDegreesPerDp,
        )
        changed = true
      } else if (!rotating && options.isDragPanEnabled) {
        if (singleMotion != SingleMotion.PAN) singleVelocity.resetTracking()
        singleMotion = SingleMotion.PAN
        session.moveBy(deltaX, deltaY)
        changed = true
      }
    }
    if (changed) {
      singleVelocity.addPointerInputChange(change)
      change.consume()
    }
  }

  private fun onTwoFinger(
    event: PointerEvent,
    first: PointerInputChange,
    second: PointerInputChange,
  ) {
    val current = TwoFingerSample(first, second)
    if (!mode.isTwoFinger) {
      cancelLongClick()
      // A gesture from another pointer family breaks the mouse/touch click sequence. Otherwise a
      // mouse click immediately after a touch transform can be mistaken for the second click of a
      // mouse double-click that began before the fingers went down.
      lastClickAt = null
      clickOrigin = null
      quickZoomCandidate = false
      lastSingle = null
      mode = Mode.TWO_FINGER_UNDECIDED
      twoFingerStart = current
      twoFingerPrevious = current
      twoFingerPanning = false
      scaleAlongsideRotation = false
      twoFingerVelocity.resetTracking()
      twoFingerVelocity.addPointerInputChange(first)
      cancelMomentumForNewGesture()
      deferredTwoFingerVelocity = null
      if (first.type != PointerType.Mouse && second.type != PointerType.Mouse) {
        twoFingerTap =
          TwoFingerTapCandidate(
            startedAtMillis = min(pressStartedAtMillis, current.uptimeMillis),
            firstId = first.id,
            secondId = second.id,
            firstOrigin = first.position,
            secondOrigin = second.position,
          )
      }
      session.cancelTransitions()
      return
    }

    val start =
      twoFingerStart
        ?: run {
          twoFingerStart = current
          twoFingerPrevious = current
          return
        }
    val previous = twoFingerPrevious ?: start
    if (!current.hasSamePointers(previous)) {
      // A third finger replacing one of the original pair must not create a discontinuous camera
      // jump. Android's detectors reset their distance baselines on pointer configuration changes.
      twoFingerStart = current
      twoFingerPrevious = current
      twoFingerPanning = false
      scaleAlongsideRotation = false
      twoFingerTap = null
      deferredTwoFingerVelocity = null
      twoFingerVelocity.resetTracking()
      twoFingerVelocity.addPointerInputChange(first)
      return
    }
    if (!ClassicAndroidGestureMath.hasStablePressure(current.pressure, previous.pressure)) {
      twoFingerPrevious = current
      return
    }
    if (current.distance < minimumTwoFingerSpanPx || previous.distance <= 0.0) {
      twoFingerStart = current
      twoFingerPrevious = current
      twoFingerVelocity.resetTracking()
      twoFingerVelocity.addPointerInputChange(first)
      return
    }

    val centroidDelta = current.centroid - previous.centroid
    val centroidFromStart = current.centroid - start.centroid
    val scale = current.distance / previous.distance
    // StandardScaleGestureDetector defines span as twice the pointer distance around the focal
    // point, while its scale factor remains the ordinary current/previous distance ratio.
    val spanFromStartDp = (current.distance - start.distance) * 2.0 / density.density
    val spanFromPreviousDp = (current.distance - previous.distance) * 2.0 / density.density
    val rotation = Math.toDegrees(normalizedAngle(current.angle - previous.angle))
    val rotationFromStart = Math.toDegrees(normalizedAngle(current.angle - start.angle))
    val elapsedMillis = (current.uptimeMillis - previous.uptimeMillis).coerceAtLeast(0L)

    if (mode == Mode.TWO_FINGER_UNDECIDED) {
      mode =
        classifyTwoFinger(
          current = current,
          spanFromStartDp = spanFromStartDp,
          spanFromPreviousDp = spanFromPreviousDp,
          rotationFromStart = rotationFromStart,
          rotationFromPrevious = rotation,
          elapsedMillis = elapsedMillis,
          centroidFromStart = centroidFromStart,
        )
      if (mode != Mode.TWO_FINGER_UNDECIDED) {
        twoFingerTap = null
        beginGesture()
        twoFingerVelocity.resetTracking()
      }
    }

    if (
      mode == Mode.TWO_FINGER_ROTATE &&
        !scaleAlongsideRotation &&
        options.isPinchZoomEnabled &&
        abs(spanFromStartDp) >= ClassicAndroidGestureMath.SCALE_START_WHILE_ROTATING_DP &&
        ClassicAndroidGestureMath.shouldStartScale(
          spanFromStartDp,
          spanFromPreviousDp,
          elapsedMillis,
          rotation,
        )
    ) {
      // Android interrupts scale when rotation starts, then permits it again only after 75 dp.
      scaleAlongsideRotation = true
    }

    if (
      mode != Mode.TWO_FINGER_TILT &&
        options.isDragPanEnabled &&
        !twoFingerPanning &&
        current.anyPointerOutsideAxisAlignedSlop(start, panSlopPx)
    ) {
      twoFingerPanning = true
      twoFingerTap = null
      beginGesture()
    }

    var changed = false
    if (mode == Mode.TWO_FINGER_TILT) {
      if (options.isTwoFingerTiltEnabled && centroidDelta.y != 0f) {
        session.rotateAndPitchBy(
          bearingDelta = 0.0,
          pitchDelta = centroidDelta.y.toDouble() * options.twoFingerTiltDegreesPerPixel,
        )
        changed = true
      }
    } else {
      val anchor = current.centroid.toLogicalDpOffset(density)
      if (twoFingerPanning && centroidDelta != Offset.Zero) {
        session.moveBy(
          centroidDelta.x.toDouble() / density.density,
          centroidDelta.y.toDouble() / density.density,
        )
        changed = true
      }
      if (
        (mode == Mode.TWO_FINGER_SCALE || scaleAlongsideRotation) &&
          options.isPinchZoomEnabled &&
          scale.isFinite() &&
          abs(scale - 1.0) >= SCALE_EPSILON
      ) {
        session.scaleBy(ClassicAndroidGestureMath.pinchScale(scale), options.zoomAnchor(anchor))
        lastSpanDeltaPixels = abs(current.distance - previous.distance) * 2.0
        lastScaleWasOut = scale < 1.0
        lastTwoFingerAnchor = options.zoomAnchor(anchor)
        changed = true
      }
      if (
        mode == Mode.TWO_FINGER_ROTATE &&
          options.isTwoFingerRotateEnabled &&
          abs(rotation) >= ROTATION_EPSILON_DEGREES
      ) {
        // Turning the fingers clockwise rotates the map clockwise, which decreases map bearing.
        val rotationAnchor = options.zoomAnchor(anchor)
        session.rotateAndPitchBy(
          bearingDelta = -rotation,
          pitchDelta = 0.0,
          anchor = rotationAnchor,
        )
        lastTwoFingerAnchor = rotationAnchor
        lastTwoFingerCentroidPixels = current.centroid
        lastClassicRotationDegrees = -rotation
        changed = true
      }
    }

    twoFingerPrevious = current
    twoFingerVelocity.addPointerInputChange(first)
    if (changed) event.changes.forEach(PointerInputChange::consume)
  }

  private fun classifyTwoFinger(
    current: TwoFingerSample,
    spanFromStartDp: Double,
    spanFromPreviousDp: Double,
    rotationFromStart: Double,
    rotationFromPrevious: Double,
    elapsedMillis: Long,
    centroidFromStart: Offset,
  ): Mode {
    return when {
      options.isTwoFingerRotateEnabled &&
        ClassicAndroidGestureMath.shouldStartRotation(
          rotationFromStart,
          rotationFromPrevious,
          elapsedMillis,
        ) -> Mode.TWO_FINGER_ROTATE
      options.isPinchZoomEnabled &&
        abs(current.distance - (twoFingerStart?.distance ?: current.distance)) * 2.0 >=
          scaleSlopPx &&
        ClassicAndroidGestureMath.shouldStartScale(
          spanFromStartDp,
          spanFromPreviousDp,
          elapsedMillis,
          rotationFromPrevious,
        ) -> Mode.TWO_FINGER_SCALE
      options.isTwoFingerTiltEnabled &&
        abs(centroidFromStart.y) >= shoveSlopPx &&
        ClassicAndroidGestureMath.shouldStartShove(
          (centroidFromStart.y / density.density).toDouble(),
          current.fingerAngleFromHorizontalDegrees,
        ) -> Mode.TWO_FINGER_TILT
      else -> Mode.TWO_FINGER_UNDECIDED
    }
  }

  /** A pointer-up ends a drag, or completes a click if the press never travelled past the slop. */
  private fun onRelease(event: PointerEvent) {
    val origin = clickOrigin
    val completedTwoFingerTap = twoFingerTap?.takeIf { it.isComplete(event) }
    cancelLongClick()
    val continuationDuration =
      listOfNotNull(
          finishSingleVelocity(),
          deferredTwoFingerVelocity?.invoke() ?: finishTwoFingerVelocity(),
        )
        .maxOrNull()
    deferredTwoFingerVelocity = null
    lastSingle = null
    singleDragOrigin = null
    singleMotion = SingleMotion.NONE
    twoFingerStart = null
    twoFingerPrevious = null
    twoFingerPanning = false
    scaleAlongsideRotation = false
    clickOrigin = null
    quickZoomCandidate = false
    twoFingerTap = null
    mode = Mode.NONE

    if (gestureInProgress) endDrag(continuationDuration ?: Duration.ZERO)
    else if (completedTwoFingerTap != null && options.isTwoFingerTapZoomEnabled) {
      session.scaleBy(
        scale = zoomLevelsToScale(-options.zoomStep),
        anchor = options.zoomAnchor(completedTwoFingerTap.centroid.toLogicalDpOffset(density)),
        duration = options.animationDuration,
      )
    } else if (origin != null) onClick(origin, event.changes.firstOrNull()?.uptimeMillis ?: 0L)
  }

  private fun onClick(origin: Offset, timeMillis: Long) {
    val where = origin.toLogicalDpOffset(density)
    if (pressedSecondary) {
      session.onSecondaryClick(where)
      return
    }

    if (isDoubleClick(origin, timeMillis) && options.isDoubleClickZoomEnabled) {
      // Anchored at the pointer so the point under it stays put; shift inverts the direction.
      session.scaleBy(
        scale = zoomLevelsToScale(if (pressedShifted) -options.zoomStep else options.zoomStep),
        anchor = options.zoomAnchor(where),
        duration = options.animationDuration,
      )
      // Cleared so a third click starts a new pair rather than zooming again.
      lastClickAt = null
      cancelPendingTouchClick()
    } else {
      if (pressedType == PointerType.Mouse) {
        // Mouse clicks are immediate; touch taps wait so a double tap never leaks a map click.
        session.onPrimaryClick(where)
      } else {
        flushPendingTouchClick()
        lateinit var job: Job
        job = scope.launch {
          delay(doubleClickTimeoutMillis)
          if (pendingTouchClick?.job == job) {
            pendingTouchClick = null
            session.onPrimaryClick(where)
          }
        }
        pendingTouchClick = PendingTouchClick(where, job)
      }
      lastClickAt = timeMillis
      lastClickOrigin = origin
      lastClickType = pressedType
    }
  }

  /** Compose reports no click count on desktop, so a double click is a time plus a distance. */
  private fun isDoubleClick(origin: Offset, timeMillis: Long): Boolean {
    val previousAt = lastClickAt ?: return false
    return timeMillis - previousAt <= doubleClickTimeoutMillis &&
      pressedType == lastClickType &&
      (origin - lastClickOrigin).getDistance() <= slopPx()
  }

  /**
   * A finger needs far more room than a mouse, so the pressed pointer decides which slop applies.
   */
  private fun slopPx(): Float = if (pressedType == PointerType.Mouse) clickSlopPx else scaleSlopPx

  private fun dragSlopPx(): Float = if (pressedType == PointerType.Mouse) clickSlopPx else panSlopPx

  private fun viewportCenter(viewportSize: IntSize = this.viewportSize()): DpOffset? =
    if (options.isDragPanEnabled) {
      Offset(viewportSize.width / 2f, viewportSize.height / 2f).toLogicalDpOffset(density)
    } else {
      null
    }

  private fun updateTwoFingerTap(event: PointerEvent, pressedCount: Int) {
    val candidate = twoFingerTap ?: return
    if (pressedCount > 2 || !candidate.update(event, twoFingerTapSlopPx)) twoFingerTap = null
  }

  private fun finishSingleVelocity(): Duration? {
    if (!gestureInProgress) return null
    val velocity = runCatching { singleVelocity.calculateVelocity() }.getOrNull() ?: return null
    return when (singleMotion) {
      SingleMotion.PAN -> {
        if (!options.isFlingEnabled) return null
        val fling =
          ClassicAndroidGestureMath.fling(
            (velocity.x / density.density).toDouble(),
            (velocity.y / density.density).toDouble(),
            session.getCameraPosition().tilt,
          ) ?: return null
        session.moveBy(fling.offsetXDp, fling.offsetYDp, fling.duration)
        fling.duration
      }
      SingleMotion.QUICK_ZOOM -> {
        if (!options.isPinchZoomVelocityEnabled) return null
        val continuation =
          ClassicAndroidGestureMath.scaleVelocity(
            velocity.x.toDouble(),
            velocity.y.toDouble(),
            lastQuickZoomSpanDeltaPixels,
            density.density.toDouble(),
            scalingOut = velocity.y < 0f,
          ) ?: return null
        animateScaleVelocity(continuation, viewportCenter())
        continuation.duration
      }
      else -> null
    }
  }

  private fun finishTwoFingerVelocity(): Duration? = prepareTwoFingerVelocity()?.invoke()

  private fun prepareTwoFingerVelocity(): (() -> Duration?)? {
    val velocity = runCatching { twoFingerVelocity.calculateVelocity() }.getOrNull() ?: return null
    val scaleContinuation =
      if (
        (mode == Mode.TWO_FINGER_SCALE || scaleAlongsideRotation) &&
          options.isPinchZoomVelocityEnabled
      ) {
        ClassicAndroidGestureMath.scaleVelocity(
          velocity.x.toDouble(),
          velocity.y.toDouble(),
          lastSpanDeltaPixels,
          density.density.toDouble(),
          lastScaleWasOut,
        )
      } else {
        null
      }
    val rotationContinuation =
      if (mode == Mode.TWO_FINGER_ROTATE && options.isRotateVelocityEnabled) {
        ClassicAndroidGestureMath.rotationVelocity(
          velocity.x.toDouble(),
          velocity.y.toDouble(),
          lastTwoFingerCentroidPixels.x.toDouble(),
          lastTwoFingerCentroidPixels.y.toDouble(),
          lastClassicRotationDegrees,
          density.density.toDouble(),
          scaling = scaleAlongsideRotation,
        )
      } else {
        null
      }
    if (scaleContinuation == null && rotationContinuation == null) return null
    val anchor = lastTwoFingerAnchor
    return {
      scaleContinuation?.let { animateScaleVelocity(it, anchor) }
      rotationContinuation?.let { animateRotationVelocity(it, anchor) }
      listOfNotNull(scaleContinuation?.duration, rotationContinuation?.duration).maxOrNull()
    }
  }

  /** Android's scale velocity animator interpolates absolute zoom with a decelerate curve. */
  private fun animateScaleVelocity(
    continuation: ClassicAndroidGestureMath.ScaleVelocity,
    anchor: DpOffset?,
  ) {
    touchMomentum.launchScale(scope) {
      val durationNanos = continuation.duration.inWholeNanoseconds.coerceAtLeast(1L)
      val startedAt = withFrameNanos { it }
      var previousEasedProgress = 0.0
      do {
        val now = withFrameNanos { it }
        val progress = ((now - startedAt).toDouble() / durationNanos).coerceIn(0.0, 1.0)
        val easedProgress = 1.0 - (1.0 - progress).pow(2.0)
        val frameZoomDelta = continuation.zoomDelta * (easedProgress - previousEasedProgress)
        if (frameZoomDelta != 0.0) session.scaleBy(zoomLevelsToScale(frameZoomDelta), anchor)
        previousEasedProgress = easedProgress
      } while (progress < 1.0)
    }
  }

  private fun animateRotationVelocity(
    continuation: ClassicAndroidGestureMath.RotationVelocity,
    anchor: DpOffset?,
  ) {
    touchMomentum.launchRotation(scope) {
      val durationNanos = continuation.duration.inWholeNanoseconds.coerceAtLeast(1L)
      val startedAt = withFrameNanos { it }
      do {
        val now = withFrameNanos { it }
        val progress = ((now - startedAt).toDouble() / durationNanos).coerceIn(0.0, 1.0)
        // Android's default DecelerateInterpolator leaves (1 - t)^2 of the animated value.
        val frameDelta = continuation.initialDegreesPerFrame * (1.0 - progress).pow(2.0)
        if (frameDelta != 0.0) session.rotateAndPitchBy(frameDelta, 0.0, anchor = anchor)
      } while (progress < 1.0)
    }
  }

  private fun cancelPendingTouchClick() {
    pendingTouchClick?.job?.cancel()
    pendingTouchClick = null
  }

  private fun flushPendingTouchClick() {
    val pending = pendingTouchClick ?: return
    pending.job.cancel()
    pendingTouchClick = null
    session.onPrimaryClick(pending.where)
  }

  /** Keeps the camera move open through classic Android's velocity continuation. */
  private fun endDrag(followUpDuration: Duration) {
    cancelLongClick()
    if (!gestureInProgress) return
    gestureInProgress = false
    gestureEndJob?.cancel()
    if (followUpDuration > Duration.ZERO) {
      gestureEndJob = scope.launch {
        delay(followUpDuration.inWholeMilliseconds)
        gestureEndJob = null
        session.onGestureEnded()
      }
    } else {
      session.onGestureEnded()
    }
  }

  private fun beginGesture() {
    cancelLongClick()
    if (gestureInProgress) return
    gestureEndJob?.cancel()
    gestureEndJob = null
    gestureInProgress = true
    session.onGestureStarted()
  }

  /** Cancels a released gesture's inertia without closing the move a new gesture is taking over. */
  private fun cancelMomentumForNewGesture() {
    touchMomentum.cancel()
  }

  /** Clears session state if this pointer-input coroutine is replaced or disposed. */
  fun cancel() {
    val sessionGestureOpen = gestureInProgress || gestureEndJob != null
    cancelLongClick()
    cancelMomentumForNewGesture()
    gestureEndJob?.cancel()
    gestureEndJob = null
    deferredTwoFingerVelocity = null
    cancelPendingTouchClick()
    gestureInProgress = false
    if (sessionGestureOpen) session.onGestureEnded()
  }

  private fun cancelLongClick() {
    longClickJob?.cancel()
    longClickJob = null
  }

  private enum class Mode {
    NONE,
    SINGLE,
    QUICK_ZOOM,
    TWO_FINGER_UNDECIDED,
    TWO_FINGER_SCALE,
    TWO_FINGER_ROTATE,
    TWO_FINGER_TILT;

    val isTwoFinger: Boolean
      get() =
        this == TWO_FINGER_UNDECIDED ||
          this == TWO_FINGER_SCALE ||
          this == TWO_FINGER_ROTATE ||
          this == TWO_FINGER_TILT
  }

  private enum class SingleMotion {
    NONE,
    PAN,
    ROTATE_TILT,
    QUICK_ZOOM,
  }

  private data class TwoFingerSample(
    val first: Offset,
    val second: Offset,
    val firstId: PointerId? = null,
    val secondId: PointerId? = null,
    val uptimeMillis: Long = 0L,
    val pressure: Float = 1f,
  ) {
    constructor(
      first: PointerInputChange,
      second: PointerInputChange,
    ) : this(
      first.position,
      second.position,
      first.id,
      second.id,
      first.uptimeMillis,
      first.pressure,
    )

    val centroid: Offset = (first + second) / 2f
    val distance: Double = hypot((second.x - first.x).toDouble(), (second.y - first.y).toDouble())
    val angle: Double = atan2((second.y - first.y).toDouble(), (second.x - first.x).toDouble())
    val fingerAngleFromHorizontalDegrees: Double =
      abs(Math.toDegrees(angle)).let { min(it, 180.0 - it) }

    fun hasSamePointers(other: TwoFingerSample): Boolean =
      firstId == other.firstId && secondId == other.secondId

    fun anyPointerOutsideAxisAlignedSlop(other: TwoFingerSample, slopPixels: Float): Boolean =
      abs(first.x - other.first.x) >= slopPixels ||
        abs(first.y - other.first.y) >= slopPixels ||
        abs(second.x - other.second.x) >= slopPixels ||
        abs(second.y - other.second.y) >= slopPixels
  }

  private data class PendingTouchClick(val where: DpOffset, val job: Job)

  private data class TwoFingerTapCandidate(
    val startedAtMillis: Long,
    val firstId: PointerId,
    val secondId: PointerId,
    val firstOrigin: Offset,
    val secondOrigin: Offset,
    var firstCurrent: Offset = firstOrigin,
    var secondCurrent: Offset = secondOrigin,
  ) {
    val centroid: Offset
      get() = (firstCurrent + secondCurrent) / 2f

    fun update(event: PointerEvent, slopPixels: Float): Boolean {
      val now = event.changes.maxOfOrNull { it.uptimeMillis } ?: startedAtMillis
      if (now - startedAtMillis > ClassicAndroidGestureMath.TWO_FINGER_TAP_TIMEOUT_MILLIS) {
        return false
      }
      event.changes.forEach { change ->
        when (change.id) {
          firstId -> firstCurrent = change.position
          secondId -> secondCurrent = change.position
        }
      }
      return (firstCurrent - firstOrigin).getDistance() <= slopPixels &&
        (secondCurrent - secondOrigin).getDistance() <= slopPixels
    }

    fun isComplete(event: PointerEvent): Boolean =
      event.changes.none { it.pressed } &&
        (event.changes.maxOfOrNull { it.uptimeMillis } ?: startedAtMillis) - startedAtMillis <=
          ClassicAndroidGestureMath.TWO_FINGER_TAP_TIMEOUT_MILLIS
  }

  private fun normalizedAngle(radians: Double): Double =
    atan2(kotlin.math.sin(radians), kotlin.math.cos(radians))

  private companion object {
    const val SCALE_EPSILON = 0.001
    const val ROTATION_EPSILON_DEGREES = 0.1
  }
}

/** Velocity continuations shared by touch, wheel, and keyboard modifier nodes. */
internal class TouchMomentum {
  private var scaleVelocityJob: Job? = null
  private var rotationVelocityJob: Job? = null

  fun launchScale(scope: CoroutineScope, block: suspend CoroutineScope.() -> Unit) {
    scaleVelocityJob?.cancel()
    scaleVelocityJob = scope.launch(block = block)
  }

  fun launchRotation(scope: CoroutineScope, block: suspend CoroutineScope.() -> Unit) {
    rotationVelocityJob?.cancel()
    rotationVelocityJob = scope.launch(block = block)
  }

  fun cancel() {
    scaleVelocityJob?.cancel()
    scaleVelocityJob = null
    rotationVelocityJob?.cancel()
    rotationVelocityJob = null
  }
}

private fun MlnFfiMapSession.pan(
  options: GestureOptions,
  touchMomentum: TouchMomentum,
  deltaX: Double,
  deltaY: Double,
): Boolean {
  if (!options.isKeyboardPanEnabled) return false
  touchMomentum.cancel()
  moveBy(deltaX, deltaY, options.animationDuration)
  return true
}

private fun MlnFfiMapSession.zoom(
  options: GestureOptions,
  touchMomentum: TouchMomentum,
  levelDelta: Double,
): Boolean {
  if (!options.isKeyboardZoomEnabled) return false
  touchMomentum.cancel()
  scaleBy(zoomLevelsToScale(levelDelta), anchor = null, duration = options.animationDuration)
  return true
}

private fun MlnFfiMapSession.rotateAndTilt(
  options: GestureOptions,
  touchMomentum: TouchMomentum,
  bearingDelta: Double = 0.0,
  pitchDelta: Double = 0.0,
): Boolean {
  if (!options.isKeyboardRotateTiltEnabled) return false
  touchMomentum.cancel()
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
