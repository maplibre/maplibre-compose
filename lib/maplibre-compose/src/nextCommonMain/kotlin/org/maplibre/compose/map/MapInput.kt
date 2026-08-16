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
import kotlin.math.PI
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
 * Neither backend owns platform gestures: MapLibre Native declines to, and GL JS is composited
 * under Compose where its own DOM handlers never fire.
 */
internal fun Modifier.mapInput(
  target: GestureTarget,
  options: GestureOptions,
  density: Density,
  focusRequester: FocusRequester,
  continuation: GestureContinuation,
): Modifier =
  this.keyboardInput(target, options, continuation)
    .focusRequester(focusRequester)
    .focusable()
    .pointerGestures(target, options, density, focusRequester, continuation)
    .scrollZoom(target, options, density, continuation)

private fun Modifier.keyboardInput(
  target: GestureTarget,
  options: GestureOptions,
  continuation: GestureContinuation,
): Modifier =
  // Key events only reach a focused node, and the map takes focus on press, so the keyboard works
  // only after the user has interacted with the map.
  onKeyEvent { event ->
    if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
    val shifted = event.isShiftPressed
    val panStep = options.keyboardPanStep.value.toDouble()
    when (event.key) {
      Key.DirectionLeft ->
        if (shifted)
          target.rotateAndTilt(options, continuation, bearingDelta = -options.keyboardRotateStep)
        else target.pan(options, continuation, panStep, 0.0)
      Key.DirectionRight ->
        if (shifted)
          target.rotateAndTilt(options, continuation, bearingDelta = options.keyboardRotateStep)
        else target.pan(options, continuation, -panStep, 0.0)
      Key.DirectionUp ->
        if (shifted)
          target.rotateAndTilt(options, continuation, pitchDelta = options.keyboardPitchStep)
        else target.pan(options, continuation, 0.0, panStep)
      Key.DirectionDown ->
        if (shifted)
          target.rotateAndTilt(options, continuation, pitchDelta = -options.keyboardPitchStep)
        else target.pan(options, continuation, 0.0, -panStep)
      Key.Plus,
      Key.Equals -> target.zoom(options, continuation, options.zoomStep)
      Key.Minus -> target.zoom(options, continuation, -options.zoomStep)
      else -> false
    }
  }

private fun Modifier.pointerGestures(
  target: GestureTarget,
  options: GestureOptions,
  density: Density,
  focusRequester: FocusRequester,
  continuation: GestureContinuation,
): Modifier =
  pointerInput(target, options, density, continuation) {
    val scope = CoroutineScope(currentCoroutineContext())
    val gesture =
      MapPointerGesture(
        target = target,
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
        continuation = continuation,
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
  target: GestureTarget,
  options: GestureOptions,
  density: Density,
  continuation: GestureContinuation,
): Modifier =
  pointerInput(target, options, density, continuation) {
    val scope = CoroutineScope(currentCoroutineContext())
    try {
      awaitEachGesture {
        while (true) {
          val event = awaitPointerEvent()
          if (event.type != PointerEventType.Scroll) continue
          if (!options.isScrollZoomEnabled) continue
          val change = event.changes.firstOrNull() ?: continue
          if (change.scrollDelta.y == 0f) continue
          val scroll = scrollNotches(change.scrollDelta.y, density)
          continuation.interrupt()
          val token = continuation.resume() ?: target.onGestureStarted()
          target.cancelTransitions()
          target.scaleBy(
            scale = zoomLevelsToScale(-scroll * options.scrollZoomStep),
            anchor = options.zoomAnchor(change.position.toLogicalDpOffset(density)),
            gestureToken = token,
          )
          // A burst holds the gesture open so the next notch resumes the same token.
          continuation.finishAfter(scope, options.scrollZoomHold, token, target::onGestureEnded)
          change.consume()
        }
      }
    } finally {
      continuation.finish(target::onGestureEnded)
    }
  }

private class MapPointerGesture(
  private val target: GestureTarget,
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
  private val continuation: GestureContinuation,
) {
  private var gestureInProgress = false
  private var gestureToken: GestureToken? = null
  private var mode = Mode.NONE

  private var lastSingle: PointerInputChange? = null
  private var singleDragOrigin: Offset? = null
  private var singleMotion = SingleMotion.NONE
  private val singleVelocity = VelocityTracker()

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

  /** Null once the press is no longer a candidate click. Physical pixels, as Compose reports. */
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
  private var longClickHandled = false

  private var lastClickAt: Long? = null
  private var lastClickOrigin = Offset.Zero
  private var lastClickType = PointerType.Mouse
  private var pendingTouchClick: PendingTouchClick? = null

  fun onPointerEvent(event: PointerEvent) {
    // A wheel notch arrives here too, with nothing pressed, and would read as a release — closing
    // the gesture scrollZoom is holding open for the rest of the burst.
    if (event.type == PointerEventType.Scroll) return
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
    longClickHandled = false
    quickZoomCandidate =
      change.type != PointerType.Mouse &&
        options.isQuickZoomEnabled &&
        isDoubleClick(change.position, change.uptimeMillis)
    quickZoomOriginY = change.position.y
    quickZoomAppliedDelta = 0.0
    lastQuickZoomSpanDeltaPixels = 0.0
    singleMotion = SingleMotion.NONE
    singleVelocity.resetTracking()
    singleVelocity.addPointerInputChange(change)
    if (quickZoomCandidate) cancelPendingTouchClick()
    deferredTwoFingerVelocity = null
    continuation.interrupt()
    runCatching { focusRequester.requestFocus() }
    target.cancelTransitions()

    if (change.type != PointerType.Mouse && !quickZoomCandidate) {
      // Claim the touch press before parent recognizers reach their long-click timeout.
      change.consume()
      val origin = change.position
      longClickJob = scope.launch {
        delay(longClickTimeoutMillis)
        if (clickOrigin == origin && !gestureInProgress && mode == Mode.SINGLE) {
          longClickHandled = true
          clickOrigin = null
          continuation.finish(target::onGestureEnded)
          target.onSecondaryClick(origin.toLogicalDpOffset(density))
        }
      }
    }
  }

  private fun onSingleDrag(event: PointerEvent, change: PointerInputChange) {
    val previous = lastSingle ?: return
    val delta = change.position - previous.position
    lastSingle = change
    if (delta == Offset.Zero) return
    val rotating = event.buttons.isSecondaryPressed || event.keyboardModifiers.isCtrlPressed
    val canTransform =
      when {
        quickZoomCandidate -> options.isQuickZoomEnabled
        rotating -> options.isDragRotateTiltEnabled
        else -> options.isDragPanEnabled
      }

    if (!gestureInProgress) {
      val origin = singleDragOrigin
      val displacement = if (origin == null) Offset.Zero else change.position - origin
      if (quickZoomCandidate) {
        // The Android quick-scale detector doubles vertical displacement into its span. Horizontal
        // travel only disqualifies the double tap; it must never turn into a pan.
        if (abs(displacement.y) * 2f < scaleSlopPx) {
          if (abs(displacement.x) <= scaleSlopPx) return
          clickOrigin = null
          lastClickAt = null
          quickZoomCandidate = false
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
      deferredTwoFingerVelocity = null
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
      target.scaleBy(
        scale = zoomLevelsToScale(targetDelta - quickZoomAppliedDelta),
        // Android quick zoom is deliberately centred rather than finger anchored.
        anchor = viewportCenter(currentViewportSize),
        gestureToken = gestureToken,
      )
      lastQuickZoomSpanDeltaPixels = abs(delta.y) * 2.0
      quickZoomAppliedDelta = targetDelta
      changed = true
    } else {
      // Read every mouse event rather than only the press, so releasing ctrl switches to panning.
      if (rotating && options.isDragRotateTiltEnabled) {
        // Moving the remaining finger takes over from deferred pinch/rotation velocity; if it
        // merely lifts, onRelease still starts that two-finger continuation instead.
        deferredTwoFingerVelocity = null
        if (singleMotion != SingleMotion.ROTATE_TILT) singleVelocity.resetTracking()
        singleMotion = SingleMotion.ROTATE_TILT
        target.rotateAndPitchBy(
          bearingDelta = deltaX * options.dragRotateDegreesPerDp,
          pitchDelta = deltaY * options.dragPitchDegreesPerDp,
          gestureToken = gestureToken,
        )
        changed = true
      } else if (!rotating && options.isDragPanEnabled) {
        deferredTwoFingerVelocity = null
        if (singleMotion != SingleMotion.PAN) singleVelocity.resetTracking()
        singleMotion = SingleMotion.PAN
        target.moveBy(deltaX, deltaY, gestureToken = gestureToken)
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
      // A gesture from another pointer family breaks the mouse/touch click sequence.
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
      continuation.interrupt()
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
      target.cancelTransitions()
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
      // As Android's detectors do, reset the distance baselines when the pointers change, so a
      // third finger replacing one of the pair cannot jump the camera.
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
    val rotation = normalizedAngle(current.angle - previous.angle).toDegrees()
    val rotationFromStart = normalizedAngle(current.angle - start.angle).toDegrees()
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
        target.rotateAndPitchBy(
          bearingDelta = 0.0,
          pitchDelta = centroidDelta.y.toDouble() * options.twoFingerTiltDegreesPerPixel,
          gestureToken = gestureToken,
        )
        changed = true
      }
    } else {
      val anchor = current.centroid.toLogicalDpOffset(density)
      if (twoFingerPanning && centroidDelta != Offset.Zero) {
        target.moveBy(
          centroidDelta.x.toDouble() / density.density,
          centroidDelta.y.toDouble() / density.density,
          gestureToken = gestureToken,
        )
        changed = true
      }
      if (
        (mode == Mode.TWO_FINGER_SCALE || scaleAlongsideRotation) &&
          options.isPinchZoomEnabled &&
          scale.isFinite() &&
          abs(scale - 1.0) >= SCALE_EPSILON
      ) {
        target.scaleBy(
          ClassicAndroidGestureMath.pinchScale(scale),
          options.zoomAnchor(anchor),
          gestureToken = gestureToken,
        )
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
        target.rotateAndPitchBy(
          bearingDelta = -rotation,
          pitchDelta = 0.0,
          anchor = rotationAnchor,
          gestureToken = gestureToken,
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

  private fun onRelease(event: PointerEvent) {
    val origin = clickOrigin
    val handledLongClick = longClickHandled
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
    longClickHandled = false
    quickZoomCandidate = false
    twoFingerTap = null
    mode = Mode.NONE

    if (
      (!gestureInProgress && completedTwoFingerTap != null && options.isTwoFingerTapZoomEnabled) ||
        origin != null ||
        handledLongClick
    ) {
      event.changes.forEach(PointerInputChange::consume)
    }

    if (gestureInProgress) {
      endDrag(continuationDuration ?: Duration.ZERO)
      return
    }
    continuation.finish(target::onGestureEnded)
    if (completedTwoFingerTap != null && options.isTwoFingerTapZoomEnabled) {
      target.discreteGesture(continuation) { token ->
        scaleByAwaitingTransition(
          scale = zoomLevelsToScale(-options.zoomStep),
          anchor = options.zoomAnchor(completedTwoFingerTap.centroid.toLogicalDpOffset(density)),
          duration = options.animationDuration,
          gestureToken = token,
        )
      }
    } else if (origin != null) {
      onClick(origin, event.changes.firstOrNull()?.uptimeMillis ?: 0L)
    }
  }

  private fun onClick(origin: Offset, timeMillis: Long) {
    val where = origin.toLogicalDpOffset(density)
    if (pressedSecondary) {
      target.onSecondaryClick(where)
      return
    }

    if (isDoubleClick(origin, timeMillis) && options.isDoubleClickZoomEnabled) {
      // Anchored at the pointer so the point under it stays put; shift inverts the direction.
      target.discreteGesture(continuation) { token ->
        scaleByAwaitingTransition(
          scale = zoomLevelsToScale(if (pressedShifted) -options.zoomStep else options.zoomStep),
          anchor = options.zoomAnchor(where),
          duration = options.animationDuration,
          gestureToken = token,
        )
      }
      // Cleared so a third click starts a new pair rather than zooming again.
      lastClickAt = null
      cancelPendingTouchClick()
    } else {
      if (pressedType == PointerType.Mouse || !awaitsSecondTap()) {
        // Mouse clicks are immediate; touch taps wait so a double tap never leaks a map click.
        target.onPrimaryClick(where)
      } else {
        flushPendingTouchClick()
        lateinit var job: Job
        job = scope.launch {
          delay(doubleClickTimeoutMillis)
          if (pendingTouchClick?.job == job) {
            pendingTouchClick = null
            target.onPrimaryClick(where)
          }
        }
        pendingTouchClick = PendingTouchClick(where, job)
      }
      lastClickAt = timeMillis
      lastClickOrigin = origin
      lastClickType = pressedType
    }
  }

  /** Whether a second tap still has a gesture to become. */
  private fun awaitsSecondTap(): Boolean =
    options.isDoubleClickZoomEnabled || options.isQuickZoomEnabled

  /** Compose reports no click count on desktop, so a double click is a time plus a distance. */
  private fun isDoubleClick(origin: Offset, timeMillis: Long): Boolean {
    val previousAt = lastClickAt ?: return false
    return timeMillis - previousAt <= doubleClickTimeoutMillis &&
      pressedType == lastClickType &&
      (origin - lastClickOrigin).getDistance() <= slopPx()
  }

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
          ClassicAndroidGestureMath.screenSpaceFling(
            (velocity.x / density.density).toDouble(),
            (velocity.y / density.density).toDouble(),
          ) ?: return null
        // The drag already applies each pointer delta with moveBy. The fling is that same
        // virtual finger, still sliding, now decelerating. One animated moveBy would unproject
        // the whole offset and travel farther toward the horizon than away from it.
        animateFling(fling)
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
    velocity: ClassicAndroidGestureMath.ScaleVelocity,
    anchor: DpOffset?,
  ) {
    val token = gestureToken
    continuation.launchScale(scope) {
      animateDecelerating(velocity.duration) { frameFraction ->
        val frameZoomDelta = velocity.zoomDelta * frameFraction
        if (frameZoomDelta != 0.0) {
          target.scaleBy(zoomLevelsToScale(frameZoomDelta), anchor, gestureToken = token)
        }
      }
    }
  }

  private fun animateFling(fling: ClassicAndroidGestureMath.Fling) {
    val token = gestureToken
    continuation.launchFling(scope) {
      animateDecelerating(fling.duration) { frameFraction ->
        val deltaX = fling.offsetXDp * frameFraction
        val deltaY = fling.offsetYDp * frameFraction
        if (deltaX != 0.0 || deltaY != 0.0) {
          target.moveBy(deltaX, deltaY, gestureToken = token)
        }
      }
    }
  }

  /** Android's default DecelerateInterpolator: remaining motion falls as `(1 - t)^2`. */
  private suspend fun animateDecelerating(
    duration: Duration,
    apply: (frameFraction: Double) -> Unit,
  ) {
    val durationNanos = duration.inWholeNanoseconds.coerceAtLeast(1L)
    val startedAt = withFrameNanos { it }
    var previousEasedProgress = 0.0
    do {
      val now = withFrameNanos { it }
      val progress = ((now - startedAt).toDouble() / durationNanos).coerceIn(0.0, 1.0)
      val easedProgress = 1.0 - (1.0 - progress).pow(2.0)
      val frameFraction = easedProgress - previousEasedProgress
      if (frameFraction != 0.0) apply(frameFraction)
      previousEasedProgress = easedProgress
    } while (progress < 1.0)
  }

  private fun animateRotationVelocity(
    velocity: ClassicAndroidGestureMath.RotationVelocity,
    anchor: DpOffset?,
  ) {
    val token = gestureToken
    continuation.launchRotation(scope) {
      val durationNanos = velocity.duration.inWholeNanoseconds.coerceAtLeast(1L)
      val startedAt = withFrameNanos { it }
      do {
        val now = withFrameNanos { it }
        val progress = ((now - startedAt).toDouble() / durationNanos).coerceIn(0.0, 1.0)
        // Android's default DecelerateInterpolator leaves (1 - t)^2 of the animated value.
        val frameDelta = velocity.initialDegreesPerFrame * (1.0 - progress).pow(2.0)
        if (frameDelta != 0.0) {
          target.rotateAndPitchBy(frameDelta, 0.0, anchor = anchor, gestureToken = token)
        }
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
    target.onPrimaryClick(pending.where)
  }

  private fun endDrag(followUpDuration: Duration) {
    cancelLongClick()
    if (!gestureInProgress) return
    gestureInProgress = false
    val token = gestureToken ?: return
    gestureToken = null
    if (followUpDuration > Duration.ZERO) {
      continuation.finishAfter(scope, followUpDuration, token, target::onGestureEnded)
    } else {
      target.onGestureEnded(token)
    }
  }

  private fun beginGesture() {
    cancelLongClick()
    if (gestureInProgress) return
    gestureInProgress = true
    gestureToken = continuation.resume() ?: target.onGestureStarted()
  }

  fun cancel() {
    cancelLongClick()
    longClickHandled = false
    deferredTwoFingerVelocity = null
    cancelPendingTouchClick()
    if (gestureInProgress) {
      gestureInProgress = false
      continuation.cancel()
      gestureToken?.let(target::onGestureEnded)
      gestureToken = null
    } else {
      continuation.finish(target::onGestureEnded)
    }
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
      abs(angle.toDegrees()).let { min(it, 180.0 - it) }

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

internal class GestureContinuation(private val scope: CoroutineScope) {
  private var scaleVelocityJob: Job? = null
  private var rotationVelocityJob: Job? = null
  private var flingJob: Job? = null
  private var discreteTransitionJob: Job? = null
  private var finishJob: Job? = null
  private var openToken: GestureToken? = null

  fun launchScale(scope: CoroutineScope, block: suspend CoroutineScope.() -> Unit) {
    scaleVelocityJob?.cancel()
    scaleVelocityJob = scope.launch(block = block)
  }

  fun launchRotation(scope: CoroutineScope, block: suspend CoroutineScope.() -> Unit) {
    rotationVelocityJob?.cancel()
    rotationVelocityJob = scope.launch(block = block)
  }

  fun launchFling(scope: CoroutineScope, block: suspend CoroutineScope.() -> Unit) {
    flingJob?.cancel()
    flingJob = scope.launch(block = block)
  }

  fun launchDiscreteTransition(block: suspend CoroutineScope.() -> Unit) {
    discreteTransitionJob?.cancel()
    discreteTransitionJob = scope.launch(block = block)
  }

  /** Stops camera motion while leaving its gesture open for a possible pointer takeover. */
  fun interrupt() {
    scaleVelocityJob?.cancel()
    scaleVelocityJob = null
    rotationVelocityJob?.cancel()
    rotationVelocityJob = null
    flingJob?.cancel()
    flingJob = null
    discreteTransitionJob?.cancel()
    discreteTransitionJob = null
  }

  /** Takes over a gesture whose velocity continuation has not ended yet. */
  fun resume(): GestureToken? {
    val token = openToken
    finishJob?.cancel()
    finishJob = null
    openToken = null
    return token
  }

  fun finishAfter(
    scope: CoroutineScope,
    duration: Duration,
    token: GestureToken,
    onFinished: (GestureToken) -> Unit,
  ) {
    finishJob?.cancel()
    openToken = token
    finishJob = scope.launch {
      delay(duration.inWholeMilliseconds)
      finishJob = null
      openToken = null
      onFinished(token)
    }
  }

  fun finish(onFinished: (GestureToken) -> Unit) {
    interrupt()
    resume()?.let(onFinished)
  }

  /** Stops all continuation work without emitting an end event. */
  fun cancel() {
    interrupt()
    resume()
  }
}

private fun GestureTarget.pan(
  options: GestureOptions,
  continuation: GestureContinuation,
  deltaX: Double,
  deltaY: Double,
): Boolean {
  if (!options.isKeyboardPanEnabled) return false
  continuation.finish(::onGestureEnded)
  discreteGesture(continuation) { token ->
    moveByAwaitingTransition(deltaX, deltaY, options.animationDuration, token)
  }
  return true
}

private fun GestureTarget.zoom(
  options: GestureOptions,
  continuation: GestureContinuation,
  levelDelta: Double,
): Boolean {
  if (!options.isKeyboardZoomEnabled) return false
  continuation.finish(::onGestureEnded)
  discreteGesture(continuation) { token ->
    scaleByAwaitingTransition(
      zoomLevelsToScale(levelDelta),
      anchor = null,
      duration = options.animationDuration,
      gestureToken = token,
    )
  }
  return true
}

private fun GestureTarget.rotateAndTilt(
  options: GestureOptions,
  continuation: GestureContinuation,
  bearingDelta: Double = 0.0,
  pitchDelta: Double = 0.0,
): Boolean {
  if (!options.isKeyboardRotateTiltEnabled) return false
  continuation.finish(::onGestureEnded)
  discreteGesture(continuation) { token ->
    rotateAndPitchByAwaitingTransition(
      bearingDelta,
      pitchDelta,
      options.animationDuration,
      gestureToken = token,
    )
  }
  return true
}

private fun GestureTarget.discreteGesture(
  continuation: GestureContinuation,
  command: suspend GestureTarget.(GestureToken) -> Unit,
) {
  val token = onGestureStarted()
  continuation.launchDiscreteTransition {
    try {
      this@discreteGesture.command(token)
    } finally {
      onGestureEnded(token)
    }
  }
}

/** A zoom level is a doubling. */
private fun zoomLevelsToScale(levelDelta: Double): Double = 2.0.pow(levelDelta)

/**
 * Where a pointer-driven zoom should pivot: the pointer, or the viewport centre. Anchoring at the
 * pointer moves the camera target, so when panning is disabled zoom pivots on the centre instead.
 */
private fun GestureOptions.zoomAnchor(pointer: DpOffset): DpOffset? =
  if (isDragPanEnabled) pointer else null

/** Compose reports physical pixels; MapLibre projects in logical ones. */
private fun Offset.toLogicalDpOffset(density: Density): DpOffset =
  DpOffset((x / density.density).dp, (y / density.density).dp)

private fun Double.toDegrees(): Double = this * 180.0 / PI
