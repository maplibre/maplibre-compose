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

  private val pairing =
    TapPairing(scope, doubleClickMinTimeMillis, doubleClickTimeoutMillis) { sample, generation ->
      emitTap(TapFamily.Tap, sample, generation)
    }

  private var contact: Contact = Contact.Idle
  private val contactOrder = mutableListOf<PointerId>()

  // A contact group keeps these while it switches between one and two contacts.
  private var twoFingerTap: TwoFingerTapCandidate? = null
  private var pendingContinuation: PointerContinuation? = null

  /** What the pressed contacts are doing, from the first press until the last lift. */
  private sealed interface Contact {
    data object Idle : Contact

    /** The group lost its camera authority; nothing responds until every contact lifts. */
    data object Suppressed : Contact
  }

  /** One contact. [press] is null when the contact remains from a lifted pair. */
  private class Single(
    var sample: GesturePointerSample,
    val press: Press?,
    var drag: SingleDrag?,
  ) : Contact

  private class Transform(val gesture: PointerPairGesture) : Contact

  fun onPointerEvent(event: PointerEvent) {
    val oldContacts = contactOrder.toList()
    val pressedIds = event.changes.filter { it.pressed }.map { it.id }
    contactOrder.retainAll(pressedIds)
    pressedIds.forEach { if (it !in contactOrder) contactOrder.add(it) }
    val pressed = contactOrder.mapNotNull { id -> event.changes.firstOrNull { it.id == id } }

    if (contact == Contact.Suppressed) {
      if (pressed.isEmpty()) contact = Contact.Idle
      return
    }
    if (gestureToken?.acceptsCommands == false) {
      cancel()
      if (pressed.isNotEmpty()) contact = Contact.Suppressed
      return
    }

    val candidate = twoFingerTap
    if (pressed.size > 2 || candidate?.update(event, twoFingerTapSlopPx) == false)
      twoFingerTap = null

    when {
      pressed.size >= 2 -> onTwoFinger(event, pressed, oldContacts != contactOrder)
      pressed.size == 1 -> onSingle(event, pressed.single())
      // A hover, enter, or exit also has nothing pressed. Treating those as a lift would
      // cancel a fling or a keyboard ease the moment the cursor moved.
      contact != Contact.Idle -> onRelease(event)
    }
  }

  private fun onSingle(event: PointerEvent, change: PointerInputChange) {
    when (val current = contact) {
      is Single -> onSingleMove(current, event, change)
      is Transform -> {
        // Keep the remaining finger usable, but start its drag from the current position.
        // Reusing the pair origin would make the map jump when either finger lifts.
        stage(current.gesture.end())
        val sample = event.gestureSample(null, density, change.position, setOf(change.type))
        contact = Single(sample, null, drags.cameraDrag(change, sample, afterContactChange = true))
      }
      else -> onPress(event, change)
    }
  }

  private fun onPress(event: PointerEvent, change: PointerInputChange) {
    val sample = event.gestureSample(null, density, change.position, setOf(change.type))
    val secondary = change.type == PointerType.Mouse && event.buttons.isSecondaryPressed
    val tapDemand = TapFamily.entries.filterTo(mutableSetOf()) { hasTapDemand(it, sample) }
    val doubleTap = TapFamily.DoubleTap in tapDemand
    val quickZoom = quickZoomMatches(sample)
    val paired =
      pairing.press(
        change.position,
        change.uptimeMillis,
        change.type,
        if (change.type == PointerType.Mouse) clickSlopPx else doubleTapSlopPx,
        doubleTap = doubleTap && !secondary,
        quickZoom = quickZoom && !secondary,
      )
    val drag =
      if (paired.role == TapPairing.Press.Paired && quickZoom) SingleDrag.QuickZoom(drags, change)
      else drags.cameraDrag(change, sample)

    val clickDemand = doubleTap || quickZoom || tapDemand.any { it != TapFamily.TwoFingerTap }
    val press =
      Press(
        change.position,
        change.type,
        secondary,
        change.uptimeMillis,
        paired,
        tapDemand,
        quickZoom,
        clickable = clickDemand,
      )
    contact = Single(sample, press, drag)

    // Click candidates claim their press, including mouse clicks competing with a parent click.
    if (clickDemand || TapFamily.TwoFingerTap in tapDemand) change.consume()
    if (
      TapFamily.LongPress in tapDemand &&
        change.type != PointerType.Mouse &&
        drag !is SingleDrag.QuickZoom
    ) {
      scheduleLongClick(press)
    }
  }

  private fun acceptGesture() {
    if (!target.isGestureReady) return
    onRecognizedGesture()
    runCatching { focusRequester.requestFocus() }
    focus.engage(byKey = false)
    if (gestureToken == null) target.interruptCamera() else target.observeInput()
  }

  private fun scheduleLongClick(press: Press) {
    press.longClickJob = scope.launch {
      delay(longClickTimeoutMillis)
      val single = contact as? Single
      if (single?.press === press && press.clickable && !gestureInProgress) {
        acceptGesture()
        press.longClickHandled = true
        press.clickable = false
        // This press is a long click, including a paired second tap that was held.
        pairing.discard(emitClick = false)
        emitTap(
          TapFamily.LongPress,
          single.sample.copy(
            uptimeMillis = press.startedAtMillis + longClickTimeoutMillis,
            position = target.positionFromScreenLocation(single.sample.screenOffset),
          ),
        )
      }
    }
  }

  private fun quickZoomMatches(sample: GesturePointerSample): Boolean =
    options.camera.settings.zoom.enabled && options.bindings.tapDrag.matches(sample)

  /** Adds a lifted pair's momentum to what the group releases when its last contact lifts. */
  private fun stage(continuation: PointerContinuation?) {
    pendingContinuation = continuation?.withPrevious(pendingContinuation) ?: pendingContinuation
  }

  private fun retainCameraAuthority(): Boolean {
    if (gestureToken?.acceptsCommands == true) return true
    cancel()
    contact = Contact.Suppressed
    return false
  }

  private fun onSingleMove(single: Single, event: PointerEvent, change: PointerInputChange) {
    val sample = event.gestureSample(null, density, change.position, setOf(change.type))
    val oldSample = single.sample
    single.sample = sample
    // A mouse button change selects the drag again from the buttons and modifiers now held.
    // Modifiers alone do not, as in MapLibre GL JS. The replaced drag ends as if released, and
    // the replacement rebases at this event so it cannot apply the previous drag's movement.
    if (change.type == PointerType.Mouse && oldSample.buttons != sample.buttons) {
      val next = drags.cameraDrag(change, sample)
      if (next?.response != single.drag?.response) {
        single.press?.stopClick()
        releaseDrag(single.drag, sample)
        endDrag()
        single.drag = next
        return
      }
    }

    val press = single.press
    if (press != null && (change.position - press.origin).getDistance() > press.clickSlop())
      press.stopClick()
    val drag = single.drag ?: return
    val motion = drag.move(change)
    if (drag.rejected) {
      single.drag = null
      return
    }
    if (motion == null) return

    if (motion.started) {
      press?.stopClick()
      twoFingerTap = null
      // A replacement only takes over the camera components it controls.
      pendingContinuation = pendingContinuation?.without(drag.components)
      beginGesture()
      drag.components.forEach { gestureToken?.rearm(it) }
      pairing.discard(emitClick = drag !is SingleDrag.QuickZoom)
    }

    if (!retainCameraAuthority()) return
    drag.update(motion.delta, change, sample, gestureToken)
    change.consume()
  }

  /** Runs a started drag's release and the momentum staged for the camera session. */
  private fun releaseDrag(drag: SingleDrag?, sample: GesturePointerSample) {
    val completed = drag?.takeIf { it.active }
    completed?.release(sample, cameraSession)
    val momentum = completed?.takeIf { gestureInProgress }?.momentum(sample)
    val continuation = momentum?.withPrevious(pendingContinuation) ?: pendingContinuation
    pendingContinuation = null
    continuation?.settled()?.let(::animateContinuation)
  }

  private fun selectPair(
    event: PointerEvent,
    pressed: List<PointerInputChange>,
  ): Pair<PointerInputChange, PointerInputChange> {
    val selected = (contact as? Transform)?.gesture
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
    pressed: List<PointerInputChange>,
    contactsChanged: Boolean,
  ) {
    val (first, second) = selectPair(event, pressed)
    when (val current = contact) {
      is Transform if current.gesture.matches(first, second) -> {
        // Do not interpret a contact-set change as movement of the already selected pair.
        if (contactsChanged) current.gesture.rebase(first, second)
        else current.gesture.move(event, first, second)
        return
      }
      is Transform -> {
        stage(current.gesture.end())
        twoFingerTap = null
      }
      else -> {
        val single = current as? Single
        single?.drag?.cancel()
        single?.press?.stopClick()
        pairing.discard(emitClick = true)
        twoFingerTap = twoFingerTapCandidate(event, first, second, single?.press?.startedAtMillis)
      }
    }

    contact =
      Transform(
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
      )
  }

  private fun twoFingerTapCandidate(
    event: PointerEvent,
    first: PointerInputChange,
    second: PointerInputChange,
    pressedAtMillis: Long?,
  ): TwoFingerTapCandidate? {
    if (first.type == PointerType.Mouse || second.type == PointerType.Mouse) return null
    val sample =
      event.gestureSample(
        null,
        density,
        (first.position + second.position) / 2f,
        setOf(first.type, second.type),
      )
    if (!hasTapDemand(TapFamily.TwoFingerTap, sample)) return null
    return TwoFingerTapCandidate(
      min(pressedAtMillis ?: sample.uptimeMillis, sample.uptimeMillis),
      first.id,
      second.id,
      first.position,
      second.position,
      sample.pointerTypes,
    )
  }

  private fun onRelease(event: PointerEvent) {
    val released = contact
    contact = Contact.Idle
    val single = released as? Single
    val completedTwoFingerTap = twoFingerTap?.takeIf { it.isComplete(event) }
    twoFingerTap = null

    if (released is Transform) stage(released.gesture.end())
    releaseDrag(single?.drag, event.gestureSample(null, density))

    val press = single?.press
    val click = press?.takeIf { it.clickable }
    press?.stopClick()
    // A paired press that ended without a click or drag claimed the first tap; drop it.
    if (press?.role == TapPairing.Press.Paired && click == null) pairing.discard(emitClick = false)

    if (gestureInProgress) {
      if (press?.longClickHandled == true) event.consumeAll()
      endDrag()
      return
    }
    when {
      completedTwoFingerTap != null -> {
        if (options.bindings.twoFingerTap.enabled) event.consumeAll()
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
      }
      click != null -> {
        event.consumeAll()
        if (click.role != TapPairing.Press.Bounce) {
          acceptGesture()
          onClick(event, click, checkNotNull(single).sample)
        }
      }
      press?.longClickHandled == true -> event.consumeAll()
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

  private fun onClick(event: PointerEvent, click: Press, pressSample: GesturePointerSample) {
    val sample = event.gestureSample(target, density, click.origin, setOf(click.type))
    // Release no longer reports the button, but a secondary click must retain its press metadata.
    val clickSample = sample.copy(buttons = pressSample.buttons)
    val demand = click.tapDemand
    if (click.secondary) {
      if (TapFamily.SecondaryClick in demand) emitTap(TapFamily.SecondaryClick, clickSample)
      pairing.discard(emitClick = false)
      return
    }

    if (click.pairing.doubleTap) {
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

  /** Momentum staged by lifted pairs belongs to the session and is dropped with it. */
  private fun cancelCameraSession() {
    val previous = cameraSession
    cameraSession = null
    pendingContinuation = null
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
          val contactsRemain = contact != Contact.Idle
          cancel()
          if (contactsRemain) contact = Contact.Suppressed
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

  /** A suppressed group stays suppressed until its contacts lift. */
  private fun cancelContact() {
    when (val current = contact) {
      is Single -> {
        current.drag?.cancel()
        current.press?.stopClick()
        contact = Contact.Idle
      }
      is Transform -> {
        current.gesture.cancel()
        contact = Contact.Idle
      }
      Contact.Idle,
      Contact.Suppressed -> Unit
    }
    pendingContinuation = null
    twoFingerTap = null
    contactOrder.clear()
    cancelCameraSession()
  }

  /** One contact that may still end as a tap, click, or long press. Physical pixels. */
  private inner class Press(
    val origin: Offset,
    val type: PointerType,
    val secondary: Boolean,
    val startedAtMillis: Long,
    val pairing: TapPairing.Pairing,
    val tapDemand: Set<TapFamily>,
    val quickZoom: Boolean,
    /** False once the press moves, drags, or is held too long to click. */
    var clickable: Boolean,
  ) {
    var longClickJob: Job? = null
    var longClickHandled = false

    val role: TapPairing.Press
      get() = pairing.role

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

private fun PointerEvent.consumeAll() = changes.forEach(PointerInputChange::consume)
