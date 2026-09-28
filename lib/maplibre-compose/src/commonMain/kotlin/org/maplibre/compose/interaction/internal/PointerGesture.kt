package org.maplibre.compose.interaction.internal

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
import kotlin.math.min
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.maplibre.compose.camera.internal.CameraInputTarget
import org.maplibre.compose.camera.internal.CameraInputToken
import org.maplibre.compose.camera.internal.inputRotateAndPitchBy
import org.maplibre.compose.camera.internal.inputScaleBy
import org.maplibre.compose.camera.internal.inputScaleByAwaitingTransition
import org.maplibre.compose.interaction.HapticEmphasis
import org.maplibre.compose.interaction.TapResponse

internal class PointerGesture(
  private val target: CameraInputTarget,
  private val taps: TapDispatcher,
  private val options: InputConfiguration,
  boxZoom: BoxZoomPreview,
  private val density: Density,
  private val focusRequester: FocusRequester,
  private val focus: InputFocus,
  viewportSize: () -> IntSize,
  private val clickSlopPx: Float,
  private val touchSlopPx: Float,
  private val maximumFlingVelocity: Float,
  private val twoFingerTapSlopPx: Float,
  private val doubleTapSlopPx: Float,
  doubleClickMinTimeMillis: Long,
  doubleClickTimeoutMillis: Long,
  private val longClickTimeoutMillis: Long,
  private val scope: CoroutineScope,
  private val onRecognizedGesture: () -> Unit,
  private val onHaptic: ((HapticEmphasis) -> Unit)? = null,
) {
  private val gestureToken: CameraInputToken?
    get() = cameraSession?.token

  private val gestureInProgress: Boolean
    get() = gestureToken != null

  private var cameraSession: GestureInputSession? = null

  private val drags =
    DragContext(
      target,
      options,
      density,
      boxZoom,
      viewportSize,
      touchSlopPx,
      maximumFlingVelocity,
    )
  private var drag: SingleDrag? = null
  private var dragSample: GesturePointerSample? = null
  private var suppressedUntilRelease = false
  private var lastSingle: PointerInputChange? = null

  private var pair: PointerPairGesture? = null
  private val contactOrder = mutableListOf<PointerId>()
  private var twoFingerTap: TwoFingerTapCandidate? = null
  private var pendingContinuation: PointerContinuation? = null

  /** The single contact's press, until it lifts or a second contact joins it. */
  private var press: Press? = null

  private val pairing =
    TapPairing(scope, doubleClickMinTimeMillis, doubleClickTimeoutMillis) { sample, generation ->
      emitTap(TapFamily.Tap, sample, generation)
    }

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
      cancel()
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
      pendingContinuation != null

  private fun onSingle(event: PointerEvent, change: PointerInputChange) {
    if (pair != null) {
      // Keep the remaining finger usable, but start its drag from the current position.
      // Reusing the pair origin would make the map jump when either finger lifts.
      val completed = pair
      pendingContinuation =
        completed?.end()?.withPrevious(pendingContinuation) ?: pendingContinuation
      pair = null
      if (gestureToken?.acceptsCommands == false) {
        retainCameraAuthority()
        return
      }

      lastSingle = change
      val sample = event.gestureSample(null, density, change.position, setOf(change.type))
      dragSample = sample
      drag = drags.cameraDrag(change, sample, afterContactChange = true)
      return
    }

    if (lastSingle == null) onPress(event, change) else onSingleDrag(event, change)
  }

  private fun onPress(event: PointerEvent, change: PointerInputChange) {
    lastSingle = change
    val sample = event.gestureSample(null, density, change.position, setOf(change.type))
    val secondary = change.type == PointerType.Mouse && event.buttons.isSecondaryPressed
    val tapDemand = TapFamily.entries.filterTo(mutableSetOf()) { hasTapDemand(it, sample) }
    val doubleTap = TapFamily.DoubleTap in tapDemand
    val quickZoom = quickZoomMatches(sample)
    val role =
      pairing.press(
        change.position,
        change.uptimeMillis,
        change.type,
        if (change.type == PointerType.Mouse) clickSlopPx else doubleTapSlopPx,
        doubleTap = doubleTap && !secondary,
        quickZoom = quickZoom && !secondary,
      )

    dragSample = sample
    drag =
      if (role == TapPairing.Press.Paired && quickZoom) SingleDrag.QuickZoom(drags, change)
      else drags.cameraDrag(change, sample)

    val clickDemand = doubleTap || quickZoom || tapDemand.any { it != TapFamily.TwoFingerTap }
    val current =
      Press(
        change.position,
        change.type,
        secondary,
        change.uptimeMillis,
        role,
        tapDemand,
        quickZoom,
        clickable = clickDemand,
      )
    press = current
    if (
      drag == null &&
        !clickDemand &&
        !options.bindings.transform.hasDemand(sample, options.camera.settings) &&
        TapFamily.TwoFingerTap !in tapDemand
    )
      return

    pendingContinuation = null

    // Click candidates claim their press, including mouse clicks competing with a parent click.
    if (clickDemand || TapFamily.TwoFingerTap in tapDemand) change.consume()
    if (
      TapFamily.LongPress in tapDemand &&
        change.type != PointerType.Mouse &&
        drag !is SingleDrag.QuickZoom
    ) {
      scheduleLongClick(current)
    }
  }

  private fun acceptGesture() {
    if (!target.isGestureReady) return
    onRecognizedGesture()
    runCatching { focusRequester.requestFocus() }
    focus.engage(byKey = false)
    if (gestureToken == null) target.interruptCamera() else target.observeInput()
  }

  private fun scheduleLongClick(current: Press) {
    current.longClickJob = scope.launch {
      delay(longClickTimeoutMillis)
      if (press === current && current.clickable && !gestureInProgress) {
        acceptGesture()
        current.longClickHandled = true
        current.clickable = false
        // This press is a long click, including a paired second tap that was held.
        pairing.discard(emitClick = false)
        val last = checkNotNull(dragSample)
        emitTap(
          TapFamily.LongPress,
          last.copy(
            uptimeMillis = current.startedAtMillis + longClickTimeoutMillis,
            position = target.positionFromScreenLocation(last.screenOffset),
          ),
        )
      }
    }
  }

  private fun quickZoomMatches(sample: GesturePointerSample): Boolean =
    options.camera.settings.zoom.enabled && options.bindings.tapDrag.matches(sample)

  private fun cancelDrag() {
    drag?.cancel()
    drag = null
  }

  private fun retainCameraAuthority(): Boolean {
    if (gestureToken?.acceptsCommands == true) return true
    cancel()
    suppressedUntilRelease = true
    return false
  }

  private fun onSingleDrag(event: PointerEvent, change: PointerInputChange) {
    lastSingle = change
    val sample =
      event.gestureSample(
        null,
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
      val next = drags.cameraDrag(change, sample)
      if (next?.response != drag?.response) {
        cancelDrag()
        cancelCameraSession()
        drag = next
        press?.stopClick()
        return
      }
    }

    val current = press
    if (current != null && (change.position - current.origin).getDistance() > current.clickSlop())
      current.stopClick()
    val moving = drag ?: return
    val motion = moving.move(change)
    if (moving.rejected) {
      drag = null
      return
    }
    if (motion == null) return

    if (motion.started) {
      press?.stopClick()
      twoFingerTap = null
      // A replacement only takes over the camera components it controls.
      pendingContinuation = pendingContinuation?.without(moving.components)
      beginGesture()
      moving.components.forEach { gestureToken?.rearm(it) }
      pairing.discard(emitClick = moving !is SingleDrag.QuickZoom)
    }

    if (!retainCameraAuthority()) return
    moving.update(motion.delta, change, sample, gestureToken)
    change.consume()
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
            null,
            density,
            (a.position + b.position) / 2f,
            setOf(a.type, b.type),
          )
        if (
          options.bindings.transform.hasDemand(sample, options.camera.settings) ||
            hasTapDemand(TapFamily.TwoFingerTap, sample)
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
        // Do not interpret a contact-set change as movement of the already selected pair.
        previous.rebase(first, second)
      } else previous.move(event, first, second)
      return
    }

    if (previous != null) {
      pendingContinuation = previous.end()?.withPrevious(pendingContinuation) ?: pendingContinuation
      pair = null
      twoFingerTap = null
      if (gestureToken?.acceptsCommands == false) {
        retainCameraAuthority()
        return
      }
    } else {
      cancelDrag()
      if (gestureToken?.acceptsCommands == false) {
        retainCameraAuthority()
        return
      }
      val pressedAtMillis = press?.startedAtMillis
      press?.stopClick()
      press = null
      pairing.discard(emitClick = true)
      lastSingle = null
      if (first.type != PointerType.Mouse && second.type != PointerType.Mouse) {
        val sample =
          event.gestureSample(
            null,
            density,
            (first.position + second.position) / 2f,
            setOf(first.type, second.type),
          )
        if (hasTapDemand(TapFamily.TwoFingerTap, sample)) {
          twoFingerTap =
            TwoFingerTapCandidate(
              min(pressedAtMillis ?: sample.uptimeMillis, sample.uptimeMillis),
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
        density,
        event,
        first,
        second,
        begin = { beginGesture() },
        onRecognized = { component ->
          twoFingerTap = null
          pendingContinuation = pendingContinuation?.without(component)
        },
        retainAuthority = ::retainCameraAuthority,
        maximumFlingVelocity = maximumFlingVelocity,
        touchSlopPx = touchSlopPx,
      )
    pair = candidate
  }

  private fun onRelease(event: PointerEvent) {
    val releaseSample = event.gestureSample(null, density)
    val completedDrag = drag?.takeIf { it.active }
    completedDrag?.release(releaseSample, cameraSession)

    val released = press
    val click = released?.takeIf { it.clickable }
    val handledLongClick = released?.longClickHandled == true
    val completedTwoFingerTap = twoFingerTap?.takeIf { it.isComplete(event) }
    released?.stopClick()
    val completed = pair
    val pairContinuation =
      completed?.end()?.withPrevious(pendingContinuation) ?: pendingContinuation
    pair = null
    if (gestureToken?.acceptsCommands == false) {
      retainCameraAuthority()
      return
    }

    val dragMomentum = completedDrag?.takeIf { gestureInProgress }?.momentum(releaseSample)
    val continuation = dragMomentum?.withPrevious(pairContinuation) ?: pairContinuation
    continuation?.settled()?.let(::animateContinuation)

    pendingContinuation = null
    lastSingle = null
    drag = null
    press = null
    twoFingerTap = null
    // A paired press that ended without a click or drag claimed the first tap; drop it.
    if (released?.role == TapPairing.Press.Paired && click == null)
      pairing.discard(emitClick = false)

    if (
      (!gestureInProgress &&
        completedTwoFingerTap != null &&
        options.bindings.twoFingerTap.enabled) || click != null || handledLongClick
    ) {
      event.changes.forEach(PointerInputChange::consume)
    }

    if (gestureInProgress) {
      endDrag()
      return
    }

    if (completedTwoFingerTap != null) {
      acceptGesture()
      emitTap(
        TapFamily.TwoFingerTap,
        event.gestureSample(
          target,
          density,
          completedTwoFingerTap.centroid,
          completedTwoFingerTap.pointerTypes,
        ),
      )
    } else if (click != null && click.role != TapPairing.Press.Bounce) {
      acceptGesture()
      onClick(event, click)
    } else if (handledLongClick) {
      pairing.discard(emitClick = false)
    }
  }

  private fun emitTap(
    family: TapFamily,
    sample: GesturePointerSample,
    generation: Long = target.inputGeneration,
  ) {
    val binding = family.binding(options)
    val action = binding.select(sample, options.camera.settings)

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

  private fun onClick(event: PointerEvent, click: Press) {
    val sample = event.gestureSample(target, density, click.origin, setOf(click.type))
    // Release no longer reports the button, but a secondary click must retain its press metadata.
    val clickSample = sample.copy(buttons = dragSample?.buttons ?: sample.buttons)
    val demand = click.tapDemand
    if (click.secondary) {
      if (TapFamily.SecondaryClick in demand) emitTap(TapFamily.SecondaryClick, clickSample)
      pairing.discard(emitClick = false)
      return
    }

    if (click.role == TapPairing.Press.Paired && TapFamily.DoubleTap in demand) {
      emitTap(TapFamily.DoubleTap, clickSample)
      pairing.discard(emitClick = false)
      return
    }

    val doubleTap = TapFamily.DoubleTap in demand
    val mouse = click.type == PointerType.Mouse
    // A touch tap waits to see whether a second tap uses it.
    if (TapFamily.Tap in demand && (mouse || (!doubleTap && !click.quickZoom)))
      emitTap(TapFamily.Tap, clickSample)
    pairing.remember(
      clickSample,
      target.inputGeneration,
      click.origin,
      click.type,
      doubleTap,
      click.quickZoom,
      clickOnExpiry = !mouse && TapFamily.Tap in demand,
    )
  }

  private fun hasTapDemand(family: TapFamily, sample: GesturePointerSample): Boolean =
    family.matches(options, sample) &&
      (taps.hasHandlers(family) ||
        family.binding(options).select(sample, options.camera.settings)?.let {
          it != TapResponse.None
        } == true)

  private fun animateContinuation(velocity: PointerContinuation) {
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
    checkNotNull(cameraSession).scope.launch {
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
    checkNotNull(cameraSession).scope.launch { target.animateFling(fling, token) }
  }

  private fun animateTiltVelocity(velocity: GestureMath.TiltVelocity) {
    val token = gestureToken
    checkNotNull(cameraSession).scope.launch {
      animateDecelerating(velocity.duration) { fraction ->
        target.inputRotateAndPitchBy(0.0, velocity.pitchDelta * fraction, gestureToken = token)
      }
    }
  }

  private fun animateRotationVelocity(
    velocity: GestureMath.RotationVelocity,
    anchor: DpOffset?,
  ) {
    val token = gestureToken
    checkNotNull(cameraSession).scope.launch {
      animateDecelerating(velocity.duration) { fraction ->
        target.inputRotateAndPitchBy(
          velocity.bearingDelta * fraction,
          0.0,
          feedback = false,
          anchor = anchor,
          gestureToken = token,
        )
      }
    }
  }

  private fun endDrag() {
    val session = cameraSession ?: return
    cameraSession = null
    session.end()
  }

  private fun cancelCameraSession() {
    val previous = cameraSession
    cameraSession = null
    previous?.cancel()
  }

  private fun beginGesture(): CameraInputToken? {
    if (gestureInProgress) {
      return gestureToken
    }

    acceptGesture()
    val token = target.onGestureStarted()
    lateinit var session: GestureInputSession
    session =
      GestureInputSession(
        scope,
        target,
        token,
        animationDuration = options.scaledAnimationDuration(),
        onHaptic = onHaptic,
      ) {
        if (cameraSession === session) {
          val contactsRemain = lastSingle != null || pair != null
          cancel()
          suppressedUntilRelease = contactsRemain
        }
      }
    cameraSession = session
    return token
  }

  /** Losing this contact does not invalidate a completed tap awaiting disambiguation. */
  fun yieldToOtherHandler() {
    pairing.discard(emitClick = true)
    cancelContact()
  }

  /** Disposal or input invalidation discards pending callbacks as well as the current contact. */
  fun cancel() {
    pairing.discard(emitClick = false)
    cancelContact()
  }

  private fun cancelContact() {
    cancelDrag()
    pair?.cancel()
    press?.stopClick()
    press = null
    pendingContinuation = null
    lastSingle = null
    twoFingerTap = null
    pair = null
    contactOrder.clear()
    dragSample = null
    cancelCameraSession()
  }

  /** One contact that may still end as a tap, click, or long press. Physical pixels. */
  private inner class Press(
    val origin: Offset,
    val type: PointerType,
    val secondary: Boolean,
    val startedAtMillis: Long,
    val role: TapPairing.Press,
    val tapDemand: Set<TapFamily>,
    val quickZoom: Boolean,
    /** False once the press moves, drags, or is held too long to click. */
    var clickable: Boolean,
  ) {
    var longClickJob: Job? = null
    var longClickHandled = false

    fun clickSlop(): Float = if (type == PointerType.Mouse) clickSlopPx else touchSlopPx

    fun stopClick() {
      clickable = false
      longClickJob?.cancel()
      longClickJob = null
    }
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
