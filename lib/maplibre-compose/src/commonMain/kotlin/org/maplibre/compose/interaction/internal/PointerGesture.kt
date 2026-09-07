package org.maplibre.compose.interaction.internal

import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.pow
import kotlin.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.maplibre.compose.camera.internal.CameraInputTarget
import org.maplibre.compose.camera.internal.CameraInputToken
import org.maplibre.compose.camera.internal.boxZoomFit
import org.maplibre.compose.camera.internal.inputFitBoundsAwaitingTransition
import org.maplibre.compose.camera.internal.inputPanBy
import org.maplibre.compose.camera.internal.inputRotateAndPitchBy
import org.maplibre.compose.camera.internal.inputScaleBy
import org.maplibre.compose.camera.internal.inputScaleByAwaitingTransition
import org.maplibre.compose.interaction.CameraInputOrigin
import org.maplibre.compose.interaction.DragEvent
import org.maplibre.compose.interaction.GestureCancellationReason
import org.maplibre.compose.interaction.MapInteractions
import org.maplibre.compose.interaction.QuickZoomDirection
import org.maplibre.compose.interaction.ScreenVelocity

internal class PointerGesture(
  private val target: CameraInputTarget,
  private val taps: TapDispatcher,
  private val options: MapInteractions,
  private val currentOptions: () -> MapInteractions,
  private val ids: GestureIds,
  private val boxZoom: BoxZoomPreview,
  private val density: Density,
  private val focusRequester: FocusRequester,
  private val focus: InputFocus,
  private val viewportSize: () -> IntSize,
  private val clickSlopPx: Float,
  private val panSlopPx: Float,
  private val touchSlopPx: Float,
  private val maximumFlingVelocity: Float,
  private val twoFingerTapSlopPx: Float,
  private val doubleTapSlopPx: Float,
  private val doubleClickMinTimeMillis: Long,
  private val doubleClickTimeoutMillis: Long,
  private val longClickTimeoutMillis: Long,
  private val scope: CoroutineScope,
  private val onAcceptedPress: () -> Unit,
) {
  private val gestureToken: CameraInputToken?
    get() = cameraSession?.token

  private val gestureInProgress: Boolean
    get() = gestureToken != null

  private var cameraSession: GestureInputSession? = null

  private sealed interface SelectedDrag {
    data class Camera(val response: DragResponse) : SelectedDrag

    data object TapDrag : SelectedDrag
  }

  private var selectedDrag: SelectedDrag? = null
  private var dragStarted = false
  private var dragSample: GesturePointerSample? = null
  private var suppressedUntilRelease = false
  private var lastSingle: PointerInputChange? = null
  private var singleDragOrigin: Offset? = null
  private var dragRecognition: PointerDrag? = null
  private val singleVelocity = GestureVelocityTracker(maximumFlingVelocity)

  private var pair: PointerPairGesture? = null
  private val contactOrder = mutableListOf<PointerId>()
  private var twoFingerTap: TwoFingerTapCandidate? = null
  private var deferredTwoFingerVelocity: PairContinuation? = null

  /** Null once the press is no longer a candidate click. Physical pixels, as Compose reports. */
  private var clickOrigin: Offset? = null
  private var pressedSecondary = false
  private var pressedType = PointerType.Mouse
  private var pressStartedAtMillis = 0L
  private var quickZoomCandidate = false
  private var quickZoomOriginY = 0f
  private var quickZoomAppliedDelta = 0.0
  private var longClickJob: Job? = null
  private var longClickHandled = false
  private var tapDemand = emptySet<TapFamily>()
  /** Eligibility is fixed at the first press, including subscriber demand. */
  private var secondTapUseful = false

  /**
   * Pairing state after a first tap. The delayed-click job exists only in [TapWait.Open]; a valid
   * second down moves to [TapWait.Claimed] and cancels that job.
   */
  private var tapWait: TapWait = TapWait.None
  /** What this press is relative to [tapWait]. */
  private var pressRole = PressRole.First

  fun onPointerEvent(event: PointerEvent) {
    val oldContacts = contactOrder.toList()
    val pressedIds = event.changes.filter { it.pressed }.map { it.id }
    contactOrder.retainAll(pressedIds)
    pressedIds.forEach { if (it !in contactOrder) contactOrder.add(it) }
    val pressed = contactOrder.mapNotNull { id -> event.changes.firstOrNull { it.id == id } }

    if (suppressedUntilRelease) {
      if (pressed.isEmpty()) suppressedUntilRelease = false
      return
    }
    if (gestureToken?.acceptsCommands == false) {
      cancel(GestureCancellationReason.CameraTakeover)
      suppressedUntilRelease = pressed.isNotEmpty()
      return
    }

    val candidate = twoFingerTap
    if (pressed.size > 2 || candidate?.update(event, twoFingerTapSlopPx) == false)
      twoFingerTap = null

    when {
      pressed.size >= 2 -> {
        val (first, second) = selectPair(event, pressed)
        onTwoFinger(event, first, second, oldContacts != contactOrder)
      }
      pressed.size == 1 -> onSingle(event, pressed.single())
      // A hover, enter, or exit also has nothing pressed. Treating those as a lift would
      // cancel a fling or a keyboard ease the moment the cursor moved.
      isAwaitingPointerRelease() -> onRelease(event)
    }
  }

  /** A lift closes the pointer we are tracking. A hover does not. */
  private fun isAwaitingPointerRelease(): Boolean =
    gestureInProgress ||
      lastSingle != null ||
      pair != null ||
      twoFingerTap != null ||
      clickOrigin != null ||
      deferredTwoFingerVelocity != null

  private fun onSingle(event: PointerEvent, change: PointerInputChange) {
    if (pair != null) {
      // Keep the remaining finger usable, but start its drag from the current position.
      // Reusing the pair origin would make the map jump when either finger lifts.
      val completed = pair
      deferredTwoFingerVelocity =
        completed
          ?.end(event.changes.maxOf { it.uptimeMillis })
          ?.withPrevious(deferredTwoFingerVelocity) ?: deferredTwoFingerVelocity
      pair = null
      if (gestureToken?.acceptsCommands == false) {
        retainCameraAuthority()
        return
      }

      lastSingle = change
      singleDragOrigin = change.position
      dragSample =
        event.gestureSample(ids.next(), target, density, change.position, setOf(change.type))
      selectedDrag = selectCameraDrag(checkNotNull(dragSample))
      dragStarted = false
      // Lifting one contact often shifts the other. Require the host's normal touch slop
      // before treating that remaining contact as a new drag.
      dragRecognition = selectedDrag?.let {
        PointerDrag(change, maxOf(touchSlopPx, dragSlop(it, mouse = false)))
      }
      singleVelocity.resetTracking()
      singleVelocity.addPosition(change.uptimeMillis, change.position)
      return
    }

    if (lastSingle == null) onPress(event, change) else onSingleDrag(event, change)
  }

  private fun onPress(event: PointerEvent, change: PointerInputChange) {
    lastSingle = change
    singleDragOrigin = change.position
    clickOrigin = change.position
    pressedSecondary = change.type == PointerType.Mouse && event.buttons.isSecondaryPressed
    pressedType = change.type
    pressStartedAtMillis = change.uptimeMillis
    longClickHandled = false

    val sample =
      event.gestureSample(ids.next(), target, density, change.position, setOf(change.type))
    pressRole = classifyPress(change.position, change.uptimeMillis, change.type, sample)
    when (pressRole) {
      PressRole.First -> discardTapWait(emitClick = true)
      PressRole.Paired -> claimOpenTap()
      PressRole.Bounce -> Unit
    }

    dragSample = sample
    selectedDrag = selectDrag(sample, paired = pressRole == PressRole.Paired)
    dragStarted = false
    quickZoomCandidate = selectedDrag == SelectedDrag.TapDrag
    dragRecognition = selectedDrag?.let { dragRecognizer(change, it) }

    tapDemand = TapFamily.entries.filterTo(mutableSetOf()) { hasTapDemand(it, sample) }

    secondTapUseful =
      TapFamily.DoubleTap in tapDemand ||
        (options.camera.zoom.enabled && options.bindings.tapDrag.matches(sample))
    val longPress = TapFamily.LongPress in tapDemand
    val clickDemand = secondTapUseful || tapDemand.any { it != TapFamily.TwoFingerTap }
    if (!clickDemand) clickOrigin = null
    if (
      selectedDrag == null &&
        !clickDemand &&
        !options.bindings.transform.hasDemand(sample, options.camera) &&
        TapFamily.TwoFingerTap !in tapDemand
    )
      return

    quickZoomOriginY = change.position.y
    quickZoomAppliedDelta = 0.0
    singleVelocity.resetTracking()
    singleVelocity.addPointerInputChange(change)
    deferredTwoFingerVelocity = null

    cancelCameraSession()
    acceptPress()

    // Click candidates claim their press, including mouse clicks competing with a parent click.
    if (clickDemand || TapFamily.TwoFingerTap in tapDemand) change.consume()
    if (longPress && change.type != PointerType.Mouse && !quickZoomCandidate) {
      scheduleLongClick(change.position)
    }
  }

  private fun acceptPress() {
    if (!target.isGestureReady) return
    onAcceptedPress()
    runCatching { focusRequester.requestFocus() }
    focus.engage(byKey = false)
    if (gestureToken == null) target.interruptCamera() else target.observeInput()
  }

  private fun scheduleLongClick(origin: Offset) {
    longClickJob = scope.launch {
      delay(longClickTimeoutMillis)
      if (clickOrigin == origin && !gestureInProgress && lastSingle != null) {
        longClickHandled = true
        clickOrigin = null
        // This press is a long click, including a paired second tap that was held.
        discardTapWait(emitClick = false)
        val last = checkNotNull(dragSample)
        emitTap(
          TapFamily.LongPress,
          last.copy(
            gestureId = ids.next(),
            uptimeMillis = pressStartedAtMillis + longClickTimeoutMillis,
            position = target.positionFromScreenLocation(last.screenOffset),
          ),
        )
      }
    }
  }

  private fun selectCameraDrag(sample: GesturePointerSample): SelectedDrag.Camera? =
    options.bindings.drag
      .select(sample, options.camera)
      ?.takeUnless { it == DragResponse.None }
      ?.let(SelectedDrag::Camera)

  private fun selectDrag(sample: GesturePointerSample, paired: Boolean): SelectedDrag? {
    if (paired && options.camera.zoom.enabled && options.bindings.tapDrag.matches(sample))
      return SelectedDrag.TapDrag
    return selectCameraDrag(sample)
  }

  private fun currentDragHandlers(): DragHandlers =
    if (selectedDrag == SelectedDrag.TapDrag) currentOptions().bindings.tapDrag.handlers
    else currentOptions().bindings.drag.handlers

  private fun dragAnchor(sample: GesturePointerSample): DpOffset? =
    when (selectedDrag) {
      SelectedDrag.TapDrag -> options.bindings.tapDrag.anchor.location(sample)
      is SelectedDrag.Camera -> options.bindings.drag.rotateTilt.anchor.location(sample)
      else -> null
    }

  private fun cancelDrag(reason: GestureCancellationReason) {
    if (!dragStarted) return
    dragStarted = false
    dragRecognition?.finish()
    try {
      dragSample?.let { currentDragHandlers().observe(DragEvent.Cancel(it, reason)) }
    } finally {
      boxZoom.clear()
    }
  }

  private fun retainCameraAuthority(): Boolean {
    if (gestureToken?.acceptsCommands == true) return true
    cancel(
      if (target.isGestureReady) GestureCancellationReason.CameraTakeover
      else GestureCancellationReason.Detached
    )
    suppressedUntilRelease = true
    return false
  }

  private fun dragSlop(binding: SelectedDrag, mouse: Boolean): Float {
    val slop =
      when (binding) {
        SelectedDrag.TapDrag -> options.bindings.tapDrag.startSlop
        is SelectedDrag.Camera ->
          when (binding.response) {
            DragResponse.Pan ->
              options.bindings.drag.pan.let { if (mouse) it.mouseStartSlop else it.startSlop }
            DragResponse.RotateTilt ->
              options.bindings.drag.rotateTilt.let {
                if (mouse) it.mouseStartSlop else it.startSlop
              }
            DragResponse.FitBounds ->
              options.bindings.drag.fitBounds.let { if (mouse) it.mouseStartSlop else it.startSlop }
            DragResponse.None -> error("An empty mapping cannot become a drag")
          }
      }
    return slop.value * density.density
  }

  private fun dragRecognizer(change: PointerInputChange, binding: SelectedDrag): PointerDrag {
    val slop = dragSlop(binding, change.type == PointerType.Mouse)
    val vertical = binding == SelectedDrag.TapDrag
    return PointerDrag(change, if (vertical) slop / 2f else slop, vertical)
  }

  private fun onSingleDrag(event: PointerEvent, change: PointerInputChange) {
    val previous = lastSingle ?: return
    var delta = change.position - previous.position
    lastSingle = change
    val sample =
      event.gestureSample(
        dragSample?.gestureId ?: ids.next(),
        target,
        density,
        change.position,
        setOf(change.type),
      )

    val oldSample = dragSample
    dragSample = sample
    // Modifier/button changes can select a different camera drag. Rebase at this event so
    // the replacement gesture cannot apply movement measured for the previous response.
    if (
      change.type == PointerType.Mouse &&
        oldSample != null &&
        (oldSample.buttons != sample.buttons || oldSample.modifierKeys != sample.modifierKeys)
    ) {
      val next = selectCameraDrag(sample)
      if (next != selectedDrag) {
        cancelDrag(GestureCancellationReason.BindingChanged)
        cancelCameraSession()
        if (next != null) acceptPress()
        selectedDrag = next
        dragRecognition = next?.let { dragRecognizer(change, it) }
        dragSample = sample.copy(gestureId = ids.next())
        singleDragOrigin = change.position
        singleVelocity.resetTracking()
        singleVelocity.addPointerInputChange(change)
        clickOrigin = null
        quickZoomCandidate = false
        cancelLongClick()
        return
      }
    }

    val binding =
      selectedDrag
        ?: run {
          if ((change.position - checkNotNull(singleDragOrigin)).getDistance() > dragSlopPx()) {
            clickOrigin = null
            cancelLongClick()
          }
          return
        }
    if (delta == Offset.Zero) return

    val motion = dragRecognition?.move(change)
    if (motion == null) {
      if (
        quickZoomCandidate &&
          abs(change.position.x - checkNotNull(singleDragOrigin).x) >
            dragSlop(binding, change.type == PointerType.Mouse)
      ) {
        clickOrigin = null
        cancelLongClick()
      }
      return
    }

    // The recognizer removes slop from the first delta; quick zoom must use that same origin.
    delta = motion.delta
    if (!dragStarted) {
      val origin = singleDragOrigin ?: change.position
      if (quickZoomCandidate) quickZoomOriginY += motion.thresholdOffset.y
      clickOrigin = null
      twoFingerTap = null
      // A replacement only takes over the camera components it controls.
      deferredTwoFingerVelocity = deferredTwoFingerVelocity?.let {
        when (binding) {
          SelectedDrag.TapDrag -> it.copy(scale = null)
          is SelectedDrag.Camera ->
            when (binding.response) {
              DragResponse.Pan -> it.copy(pan = null)
              DragResponse.RotateTilt -> it.copy(rotation = null, tilt = null)
              DragResponse.FitBounds -> null
              DragResponse.None -> it
            }
        }
      }
      if (gestureInProgress) {
        // A departing transform contact can cross slop at high speed. Estimate a new drag's
        // momentum from movement after recognition, not from that transition.
        singleVelocity.resetTracking()
        singleVelocity.addPosition(change.uptimeMillis, change.position)
      }
      beginGesture()
      dragStarted = true
      when (binding) {
        SelectedDrag.TapDrag -> gestureToken?.rearm(CameraComponent.Zoom)
        is SelectedDrag.Camera ->
          when (binding.response) {
            DragResponse.Pan -> gestureToken?.rearm(CameraComponent.Pan)
            DragResponse.RotateTilt -> {
              gestureToken?.rearm(CameraComponent.Rotate)
              gestureToken?.rearm(CameraComponent.Tilt)
            }
            else -> Unit
          }
      }
      discardTapWait(emitClick = !quickZoomCandidate)
      currentDragHandlers().observe(DragEvent.Start(sample, origin.toLogicalDpOffset(density)))
      if (!retainCameraAuthority()) return
      if (binding == SelectedDrag.Camera(DragResponse.FitBounds))
        boxZoom.start(origin.toLogicalDpOffset(density), sample.screenOffset)
    }

    singleVelocity.addPointerInputChange(change)
    currentDragHandlers().observe(DragEvent.Delta(sample, delta.toLogicalDpOffset(density)))
    if (!retainCameraAuthority()) return

    applyDragResponse(binding, sample, change.position, delta)
    change.consume()
  }

  private fun applyDragResponse(
    binding: SelectedDrag,
    sample: GesturePointerSample,
    position: Offset,
    delta: Offset,
  ) {
    val deltaX = delta.x.toDouble() / density.density
    val deltaY = delta.y.toDouble() / density.density
    when (binding) {
      is SelectedDrag.Camera ->
        when (binding.response) {
          DragResponse.Pan -> target.inputPanBy(deltaX, deltaY, gestureToken = gestureToken)
          DragResponse.RotateTilt ->
            options.bindings.drag.rotateTilt.let { settings ->
              target.inputRotateAndPitchBy(
                deltaX * settings.bearingDegreesPerDp,
                deltaY * settings.pitchDegreesPerDp,
                anchor = settings.anchor.location(sample),
                gestureToken = gestureToken,
              )
            }
          DragResponse.FitBounds -> boxZoom.move(sample.screenOffset)
          DragResponse.None -> Unit
        }
      SelectedDrag.TapDrag -> {
        val settings = options.bindings.tapDrag
        val direction = if (settings.direction == QuickZoomDirection.DownZoomsIn) 1.0 else -1.0
        val targetDelta =
          GestureMath.quickZoomDelta(
            (position.y - quickZoomOriginY).toDouble(),
            viewportSize().height.toDouble(),
            settings.zoomLevelsPerViewport * direction,
          )
        target.inputScaleBy(
          zoomLevelsToScale(targetDelta - quickZoomAppliedDelta),
          settings.anchor.location(sample),
          gestureToken = gestureToken,
        )
        quickZoomAppliedDelta = targetDelta
      }
    }
  }

  private fun selectPair(
    event: PointerEvent,
    pressed: List<PointerInputChange>,
  ): Pair<PointerInputChange, PointerInputChange> {
    val selected = pair
    val first = pressed.firstOrNull { it.id == selected?.firstId }
    val second = pressed.firstOrNull { it.id == selected?.secondId }
    if (selected?.hasDemand == true && first != null && second != null) return first to second

    // Keep an admitted pair stable. Search again only if it loses a finger or has no binding.
    for (i in 0 until pressed.lastIndex) {
      for (j in i + 1 until pressed.size) {
        val a = pressed[i]
        val b = pressed[j]
        val sample =
          event.gestureSample(
            0,
            target,
            density,
            (a.position + b.position) / 2f,
            setOf(a.type, b.type),
          )
        if (
          options.bindings.transform.hasDemand(sample, options.camera) ||
            TapFamily.TwoFingerTap in tapDemand && TapFamily.TwoFingerTap.matches(options, sample)
        )
          return a to b
      }
    }
    return pressed[0] to pressed[1]
  }

  private fun onTwoFinger(
    event: PointerEvent,
    first: PointerInputChange,
    second: PointerInputChange,
    contactsChanged: Boolean,
  ) {
    val previous = pair
    if (previous != null && previous.matches(first, second)) {
      if (contactsChanged) {
        if (previous.hasDemand && event.changes.any { it.pressed && !it.previousPressed }) {
          acceptPress()
        }
        // Do not interpret a contact-set change as movement of the already selected pair.
        previous.rebase(event, first, second)
      } else previous.move(event, first, second)
      return
    }

    if (previous != null) {
      deferredTwoFingerVelocity =
        previous
          .end(event.changes.maxOf { it.uptimeMillis })
          ?.withPrevious(deferredTwoFingerVelocity) ?: deferredTwoFingerVelocity
      pair = null
      twoFingerTap = null
      if (gestureToken?.acceptsCommands == false) {
        retainCameraAuthority()
        return
      }
    } else {
      cancelDrag(GestureCancellationReason.BindingChanged)
      if (gestureToken?.acceptsCommands == false) {
        retainCameraAuthority()
        return
      }
      selectedDrag = null
      cancelLongClick()
      discardTapWait(emitClick = true)
      clickOrigin = null
      quickZoomCandidate = false
      pressRole = PressRole.First
      lastSingle = null
      if (first.type != PointerType.Mouse && second.type != PointerType.Mouse) {
        val sample =
          event.gestureSample(
            0,
            target,
            density,
            (first.position + second.position) / 2f,
            setOf(first.type, second.type),
          )
        if (
          TapFamily.TwoFingerTap in tapDemand && TapFamily.TwoFingerTap.matches(options, sample)
        ) {
          twoFingerTap =
            TwoFingerTapCandidate(
              min(pressStartedAtMillis, sample.uptimeMillis),
              first.id,
              second.id,
              first.position,
              second.position,
              sample.pointerTypes,
            )
        }
      }
    }

    val candidate =
      PointerPairGesture(
        target,
        options,
        currentOptions,
        ids,
        density,
        event,
        first,
        second,
        begin = { beginGesture(CameraInputOrigin.Transform) },
        onRecognized = { component ->
          twoFingerTap = null
          deferredTwoFingerVelocity = deferredTwoFingerVelocity?.without(component)
        },
        retainAuthority = ::retainCameraAuthority,
        maximumFlingVelocity = maximumFlingVelocity,
      )
    pair = candidate
    if (
      (candidate.hasDemand || twoFingerTap != null) &&
        event.changes.any { it.pressed && !it.previousPressed }
    ) {
      acceptPress()
    }
  }

  private fun onRelease(event: PointerEvent) {
    val completedDrag = selectedDrag.takeIf { dragStarted }
    if (dragStarted) {
      dragStarted = false
      dragRecognition?.finish()
      val sample = event.gestureSample(checkNotNull(dragSample).gestureId, target, density)
      dragSample = sample
      boxZoom.move(sample.screenOffset)
      val selection = boxZoom.clear()
      val velocity = singleVelocity.calculateVelocity()
      currentDragHandlers()
        .observe(
          DragEvent.End(
            sample,
            ScreenVelocity(
              (velocity.x / density.density).toDouble(),
              (velocity.y / density.density).toDouble(),
            ),
          )
        )
      if (gestureToken?.acceptsCommands != true) {
        cancel(GestureCancellationReason.CameraTakeover)
        return
      }
      if (selection != null) {
        val fit = target.boxZoomFit(selection)
        val session = checkNotNull(cameraSession)
        if (fit != null && session.token.acceptsCommands) {
          session.scope.launch {
            target.inputFitBoundsAwaitingTransition(
              fit,
              options.scaledAnimationDuration(),
              session.token,
            )
          }
        }
      }
    }

    val origin = clickOrigin
    val pairedSecondTap = pressRole == PressRole.Paired
    val ignoreReleaseAsTap = pressRole == PressRole.Bounce
    val handledLongClick = longClickHandled
    val completedTwoFingerTap = twoFingerTap?.takeIf { it.isComplete(event) }
    cancelLongClick()
    val completed = pair
    val pairContinuation =
      completed
        ?.end(event.changes.maxOf { it.uptimeMillis })
        ?.withPrevious(deferredTwoFingerVelocity) ?: deferredTwoFingerVelocity
    pair = null
    if (gestureToken?.acceptsCommands == false) {
      retainCameraAuthority()
      return
    }

    // Release callbacks run before momentum; an app may take the camera during a callback.
    finishSingleVelocity(completedDrag)
    pairContinuation?.let(::finishPairVelocity)

    deferredTwoFingerVelocity = null
    lastSingle = null
    singleDragOrigin = null
    dragRecognition = null
    clickOrigin = null
    longClickHandled = false
    quickZoomCandidate = false
    pressRole = PressRole.First
    twoFingerTap = null
    selectedDrag = null

    if (
      (!gestureInProgress &&
        completedTwoFingerTap != null &&
        options.bindings.twoFingerTap.enabled) || origin != null || handledLongClick
    ) {
      event.changes.forEach(PointerInputChange::consume)
    }

    if (gestureInProgress) {
      endDrag()
      return
    }

    if (completedTwoFingerTap != null) {
      emitTap(
        TapFamily.TwoFingerTap,
        event.gestureSample(
          ids.next(),
          target,
          density,
          completedTwoFingerTap.centroid,
          completedTwoFingerTap.pointerTypes,
        ),
      )
    } else if (origin != null && !ignoreReleaseAsTap) {
      onClick(event, origin, pairedSecondTap)
    } else if (handledLongClick) {
      discardTapWait(emitClick = false)
    }
  }

  private fun emitTap(
    family: TapFamily,
    sample: GesturePointerSample,
    generation: Long = target.inputGeneration,
  ) {
    val binding = family.binding(options)
    val action = binding.select(sample, options.camera)

    taps.dispatch(family, sample) camera@{
      if (action == null || action == TapResponse.None) return@camera
      val direction = if (action == TapResponse.ZoomIn) 1.0 else -1.0
      launchTapTransition(
        scope,
        target,
        generation,
        command = { token ->
          inputScaleByAwaitingTransition(
            zoomLevelsToScale(direction * binding.zoomStep),
            binding.anchor.location(sample),
            options.scaledAnimationDuration(),
            token,
          )
        },
      )
    }
  }

  private fun onClick(event: PointerEvent, origin: Offset, pairedSecondTap: Boolean) {
    val sample = event.gestureSample(ids.next(), target, density, origin, setOf(pressedType))
    // Release no longer reports the button, but a secondary click must retain its press metadata.
    val clickSample = sample.copy(buttons = dragSample?.buttons ?: sample.buttons)
    if (pressedSecondary) {
      if (TapFamily.SecondaryClick in tapDemand) emitTap(TapFamily.SecondaryClick, clickSample)
      tapWait = TapWait.None
      return
    }

    if (pairedSecondTap && TapFamily.DoubleTap in tapDemand) {
      emitTap(TapFamily.DoubleTap, clickSample)
      tapWait = TapWait.None
      return
    }

    if (TapFamily.Tap in tapDemand && (pressedType == PointerType.Mouse || !secondTapUseful))
      emitTap(TapFamily.Tap, clickSample)
    rememberFirstTap(clickSample, origin, pressedType, sample.uptimeMillis)
  }

  private fun hasTapDemand(family: TapFamily, sample: GesturePointerSample): Boolean =
    family.matches(options, sample) &&
      (taps.hasHandlers(family) ||
        family.binding(options).select(sample, options.camera)?.let { it != TapResponse.None } ==
          true)

  /** What this down is relative to a [TapWait.Open] first tap. */
  private fun classifyPress(
    origin: Offset,
    timeMillis: Long,
    type: PointerType,
    sample: GesturePointerSample,
  ): PressRole {
    if (pressedSecondary) return PressRole.First
    val open = tapWait as? TapWait.Open ?: return PressRole.First
    val canPair =
      (TapFamily.DoubleTap in tapDemand && hasTapDemand(TapFamily.DoubleTap, sample)) ||
        (options.camera.zoom.enabled && options.bindings.tapDrag.matches(sample))
    if (!canPair) return PressRole.First

    val elapsedMillis = timeMillis - open.tap.upAt
    val withinSlop = (origin - open.tap.origin).getDistance() <= slopPx()
    if (type != open.tap.type || !withinSlop) return PressRole.First
    return when {
      elapsedMillis < doubleClickMinTimeMillis -> PressRole.Bounce
      elapsedMillis <= doubleClickTimeoutMillis -> PressRole.Paired
      else -> PressRole.First
    }
  }

  /** A valid second down claims the first tap and stops the delayed click. */
  private fun claimOpenTap() {
    val open = tapWait as? TapWait.Open ?: return
    open.tap.job?.cancel()
    tapWait = TapWait.Claimed(open.tap.copy(job = null))
  }

  /**
   * Opens the pairing window after a first tap. Touch reports the click when the window expires;
   * mouse already reported it on the up.
   */
  private fun rememberFirstTap(
    sample: GesturePointerSample,
    origin: Offset,
    type: PointerType,
    timeMillis: Long,
  ) {
    if (!secondTapUseful) {
      tapWait = TapWait.None
      return
    }

    val clickOnExpiry = type != PointerType.Mouse && TapFamily.Tap in tapDemand
    val job =
      if (clickOnExpiry) {
        lateinit var launched: Job
        launched = scope.launch {
          delay(doubleClickTimeoutMillis)
          val open = tapWait as? TapWait.Open
          if (open?.tap?.job == launched) {
            tapWait = TapWait.None
            emitTap(TapFamily.Tap, open.tap.sample, open.tap.generation)
          }
        }
        launched
      } else {
        null
      }

    tapWait =
      TapWait.Open(
        OpenTap(
          sample,
          target.inputGeneration,
          origin,
          type,
          timeMillis,
          clickOnExpiry,
          job,
        )
      )
  }

  /** Closes [tapWait]. [emitClick] reports a touch first tap that was still waiting. */
  private fun discardTapWait(emitClick: Boolean) {
    when (val wait = tapWait) {
      is TapWait.Open -> {
        wait.tap.job?.cancel()
        if (emitClick && wait.tap.clickOnExpiry)
          emitTap(TapFamily.Tap, wait.tap.sample, wait.tap.generation)
      }
      is TapWait.Claimed -> {
        if (emitClick && wait.tap.clickOnExpiry)
          emitTap(TapFamily.Tap, wait.tap.sample, wait.tap.generation)
      }
      TapWait.None -> Unit
    }
    tapWait = TapWait.None
  }

  private fun slopPx(): Float =
    if (pressedType == PointerType.Mouse) clickSlopPx else doubleTapSlopPx

  private fun dragSlopPx(): Float = if (pressedType == PointerType.Mouse) clickSlopPx else panSlopPx

  private fun finishSingleVelocity(binding: SelectedDrag?) {
    if (binding == null || !gestureInProgress) return
    val velocity = singleVelocity.calculateVelocity()
    when (binding) {
      is SelectedDrag.Camera ->
        when (binding.response) {
          DragResponse.Pan -> {
            val tuning = options.camera.pan.momentum.takeIf { it.enabled } ?: return
            val fling =
              GestureMath.fling(
                (velocity.x / density.density).toDouble(),
                (velocity.y / density.density).toDouble(),
                tuning,
              ) ?: return
            animateFling(fling)
          }
          DragResponse.RotateTilt -> {
            if (!options.camera.tilt.enabled) return
            val tuning = options.camera.tilt.momentum.takeIf { it.enabled } ?: return
            val response =
              GestureMath.tiltVelocity(
                velocity.y / density.density * options.bindings.drag.rotateTilt.pitchDegreesPerDp,
                tuning,
              ) ?: return
            animateTiltVelocity(response)
          }
          else -> Unit
        }
      SelectedDrag.TapDrag -> {
        val tuning = options.bindings.tapDrag.momentum.takeIf { it.enabled } ?: return
        val direction =
          if (options.bindings.tapDrag.direction == QuickZoomDirection.DownZoomsIn) 1 else -1
        val velocityResponse =
          GestureMath.scaleVelocity(
            GestureMath.quickZoomDelta(
              velocity.y.toDouble(),
              viewportSize().height.toDouble(),
              options.bindings.tapDrag.zoomLevelsPerViewport * direction,
            ),
            tuning,
          ) ?: return
        animateScaleVelocity(velocityResponse, dragSample?.let(::dragAnchor))
      }
    }
  }

  private fun finishPairVelocity(velocity: PairContinuation) {
    velocity.pan?.let(::animateFling)
    velocity.scale?.let { animateScaleVelocity(it, velocity.scaleAnchor) }
    velocity.rotation?.let { animateRotationVelocity(it, velocity.rotationAnchor) }
    velocity.tilt?.let(::animateTiltVelocity)
  }

  /** Interpolates absolute zoom with a decelerate curve. */
  private fun animateScaleVelocity(
    velocity: GestureMath.ScaleVelocity,
    anchor: DpOffset?,
  ) {
    val token = gestureToken
    checkNotNull(cameraSession).launchMomentum(CameraComponent.Zoom) {
      animateDecelerating(velocity.duration) { frameFraction ->
        val frameZoomDelta = velocity.zoomDelta * frameFraction
        if (frameZoomDelta != 0.0) {
          target.inputScaleBy(zoomLevelsToScale(frameZoomDelta), anchor, gestureToken = token)
        }
      }
    }
  }

  private fun animateFling(fling: GestureMath.Fling) {
    val token = gestureToken
    checkNotNull(cameraSession).launchMomentum(CameraComponent.Pan) {
      animateDecelerating(fling.duration, power = 2) { frameFraction ->
        val deltaX = fling.offsetXDp * frameFraction
        val deltaY = fling.offsetYDp * frameFraction
        GestureMath.forEachScreenSpaceStep(deltaX, deltaY) { stepX, stepY ->
          target.inputPanBy(stepX, stepY, gestureToken = token)
        }
      }
    }
  }

  private fun animateTiltVelocity(velocity: GestureMath.TiltVelocity) {
    val token = gestureToken
    checkNotNull(cameraSession).launchMomentum(CameraComponent.Tilt) {
      animateDecelerating(velocity.duration) { fraction ->
        target.inputRotateAndPitchBy(0.0, velocity.pitchDelta * fraction, gestureToken = token)
      }
    }
  }

  /** Integrates displacement; velocity falls as `(1 - t)^(power - 1)`. */
  private suspend fun animateDecelerating(
    duration: Duration,
    power: Int = GestureMath.TRANSFORM_DECAY_POWER,
    apply: (frameFraction: Double) -> Unit,
  ) {
    val durationNanos = duration.inWholeNanoseconds.coerceAtLeast(1L)
    val startedAt = withFrameNanos { it }
    var previousEasedProgress = 0.0
    do {
      val now = withFrameNanos { it }
      val progress = ((now - startedAt).toDouble() / durationNanos).coerceIn(0.0, 1.0)
      val easedProgress = 1.0 - (1.0 - progress).pow(power)
      val frameFraction = easedProgress - previousEasedProgress
      if (frameFraction != 0.0) apply(frameFraction)
      previousEasedProgress = easedProgress
    } while (progress < 1.0)
  }

  private fun animateRotationVelocity(
    velocity: GestureMath.RotationVelocity,
    anchor: DpOffset?,
  ) {
    val token = gestureToken
    checkNotNull(cameraSession).launchMomentum(CameraComponent.Rotate) {
      animateDecelerating(velocity.duration) { fraction ->
        target.inputRotateAndPitchBy(
          velocity.bearingDelta * fraction,
          0.0,
          anchor = anchor,
          gestureToken = token,
        )
      }
    }
  }

  private fun endDrag() {
    cancelLongClick()
    val session = cameraSession ?: return
    cameraSession = null
    session.end()
  }

  private fun cancelCameraSession() {
    val previous = cameraSession
    cameraSession = null
    previous?.cancel()
  }

  private fun beginGesture(
    origin: CameraInputOrigin =
      if (selectedDrag == SelectedDrag.TapDrag) CameraInputOrigin.TapDrag
      else CameraInputOrigin.Drag
  ): CameraInputToken? {
    cancelLongClick()
    if (gestureInProgress) {
      gestureToken?.origin = origin
      return gestureToken
    }

    val token = target.onGestureStarted()
    lateinit var session: GestureInputSession
    session =
      GestureInputSession(scope, target, token, origin = origin) {
        if (cameraSession === session) {
          val contactsRemain = lastSingle != null || pair != null
          cancel(
            if (target.isGestureReady) GestureCancellationReason.CameraTakeover
            else GestureCancellationReason.Detached
          )
          suppressedUntilRelease = contactsRemain
        }
      }
    cameraSession = session
    return token
  }

  fun cancel(reason: GestureCancellationReason = GestureCancellationReason.InputCancelled) {
    try {
      try {
        cancelDrag(reason)
      } finally {
        pair?.cancel(reason)
      }
    } finally {
      boxZoom.clear()
      cancelLongClick()
      longClickHandled = false
      deferredTwoFingerVelocity = null
      discardTapWait(emitClick = false)
      pressRole = PressRole.First
      cancelCameraSession()
      lastSingle = null
      singleDragOrigin = null
      dragRecognition = null
      singleVelocity.resetTracking()
      twoFingerTap = null
      pair = null
      contactOrder.clear()
      clickOrigin = null
      quickZoomCandidate = false
      selectedDrag = null
      dragSample = null
    }
  }

  private fun cancelLongClick() {
    longClickJob?.cancel()
    longClickJob = null
  }

  /**
   * Pairing window after a first tap. The delayed-click job lives only in [Open]. [Claimed] is a
   * valid second down; that job is already gone.
   */
  private sealed class TapWait {
    data object None : TapWait()

    data class Open(val tap: OpenTap) : TapWait()

    data class Claimed(val tap: OpenTap) : TapWait()
  }

  /**
   * The first tap [TapWait] is pairing.
   *
   * [clickOnExpiry] is a touch tap that waited for a second tap. A mouse click already reported on
   * the first up, so expiry only closes the window.
   */
  private data class OpenTap(
    val sample: GesturePointerSample,
    val generation: Long,
    val origin: Offset,
    val type: PointerType,
    val upAt: Long,
    val clickOnExpiry: Boolean,
    val job: Job?,
  )

  private enum class PressRole {
    First,
    Bounce,
    Paired,
  }

  private data class TwoFingerTapCandidate(
    val startedAtMillis: Long,
    val firstId: PointerId,
    val secondId: PointerId,
    val firstOrigin: Offset,
    val secondOrigin: Offset,
    val pointerTypes: Set<PointerType>,
    var firstCurrent: Offset = firstOrigin,
    var secondCurrent: Offset = secondOrigin,
  ) {
    val centroid: Offset
      get() = (firstCurrent + secondCurrent) / 2f

    fun update(event: PointerEvent, slopPixels: Float): Boolean {
      val now = event.changes.maxOfOrNull { it.uptimeMillis } ?: startedAtMillis
      if (now - startedAtMillis > GestureMath.TWO_FINGER_TAP_TIMEOUT_MILLIS) {
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
          GestureMath.TWO_FINGER_TAP_TIMEOUT_MILLIS
  }
}
