package org.maplibre.compose.interaction.internal

import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.unit.Density
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
import org.maplibre.compose.interaction.CameraAction
import org.maplibre.compose.interaction.HapticEmphasis
import org.maplibre.compose.interaction.InputAction
import org.maplibre.compose.interaction.PointerButton
import org.maplibre.compose.interaction.UnspecifiedAction

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

  private val effects = Effects()

  private val pairing =
    TapPairing(scope, doubleClickMinTimeMillis, doubleClickTimeoutMillis) { sample, generation ->
      effects.emitTap(TapFamily.Tap, sample, generation)
    }

  private var contact: Contact = Contact.Idle

  private sealed interface Contact {
    data object Idle : Contact

    /** Authority was lost; ignore the group until every contact lifts. */
    data object Suppressed : Contact
  }

  private sealed class Active(var ids: List<PointerId>, previous: Active? = null) : Contact {
    var twoFingerTap: TwoFingerTapCandidate? = previous?.twoFingerTap
    var pending: PointerContinuation? = previous?.pending

    fun stage(continuation: PointerContinuation?) {
      pending = continuation?.withPrevious(pending) ?: pending
    }

    abstract fun cancel()
  }

  /** [press] is absent for a finger remaining from a lifted pair. */
  private class Single(
    ids: List<PointerId>,
    var sample: GesturePointerSample,
    val press: Press?,
    var drag: SingleDrag?,
    previous: Active? = null,
  ) : Active(ids, previous) {
    override fun cancel() {
      drag?.cancel()
      press?.stopClick()
    }
  }

  private inner class PairContact(
    input: Input.Sample,
    first: PointerInputChange,
    second: PointerInputChange,
    previous: Active?,
  ) : Active(input.ids, previous) {
    val gesture =
      effects.pair(input, first, second) { component ->
        twoFingerTap = null
        pending = pending?.without(component)
      }

    init {
      twoFingerTap =
        if (previous is PairContact) null
        else
          twoFingerTapCandidate(input, first, second, (previous as? Single)?.press?.startedAtMillis)
    }

    override fun cancel() = gesture.cancel()
  }

  private sealed interface Input {
    class Sample(
      val event: PointerEvent,
      val pressed: List<PointerInputChange>,
      val metadata: GesturePointerSample,
    ) : Input {
      val ids = pressed.map { it.id }

      fun consume() = event.changes.forEach { it.consume() }
    }

    class Timeout(val press: Press) : Input

    class Cancel(val emitClick: Boolean, val suppress: Boolean) : Input
  }

  fun onPointerEvent(event: PointerEvent) {
    val previous = (contact as? Active)?.ids.orEmpty()
    val pressed = event.changes.filter { it.pressed }
    // Preserve admission order even when Compose changes its sample order.
    val ordered =
      previous.mapNotNull { id -> pressed.firstOrNull { it.id == id } } +
        pressed.filter { it.id !in previous }
    transition(Input.Sample(event, ordered, event.gestureSample(null, density)))
  }

  /** Losing this contact does not invalidate a completed tap awaiting disambiguation. */
  fun yieldToOtherHandler() = transition(Input.Cancel(emitClick = true, suppress = false))

  /** Disposal or input invalidation also discards pending callbacks. */
  fun cancel() = transition(Input.Cancel(emitClick = false, suppress = false))

  private fun revoke() =
    transition(Input.Cancel(emitClick = false, suppress = contact != Contact.Idle))

  /** Samples, timer identities, and revocation all enter through this transition. */
  private fun transition(event: Input) {
    if (event !is Input.Cancel && effects.authorityRevoked) revoke()
    val input =
      when (event) {
        is Input.Cancel -> {
          pairing.discard(emitClick = event.emitClick)
          (contact as? Active)?.cancel()
          contact =
            if (event.suppress || contact == Contact.Suppressed) Contact.Suppressed
            else Contact.Idle
          effects.cancelCameraSession()
          return
        }
        is Input.Timeout -> {
          onTimeout(event.press)
          return
        }
        is Input.Sample -> event
      }

    if (contact == Contact.Suppressed) {
      if (input.pressed.isEmpty()) contact = Contact.Idle
      return
    }
    val current = contact as? Active
    if (input.pressed.size > 2 || current?.twoFingerTap?.update(input, twoFingerTapSlopPx) == false)
      current?.twoFingerTap = null
    when {
      input.pressed.isEmpty() -> {
        // Hover/enter/exit in Idle must leave released momentum alone.
        contact = Contact.Idle
        if (current != null) onRelease(current, input)
      }
      input.pressed.size >= 2 -> {
        val (first, second) = selectPair(input)
        if (current is PairContact && current.gesture.matches(first, second)) {
          val contactsChanged = current.ids != input.ids
          current.ids = input.ids
          // A contact-set change cannot move the selected pair.
          if (contactsChanged) current.gesture.rebase(first, second)
          else current.gesture.move(first, second)
        } else {
          if (current is PairContact) current.stage(current.gesture.end())
          else {
            current?.cancel()
            pairing.discard(emitClick = true)
          }
          contact = PairContact(input, first, second, current)
        }
      }
      current is Single -> {
        current.ids = input.ids
        onSingleMove(current, input, input.pressed.single())
      }
      current is PairContact -> {
        current.stage(current.gesture.end())
        val change = input.pressed.single()
        val sample = input.at(change.position, setOf(change.type))
        contact =
          Single(
            input.ids,
            sample,
            null,
            drags.cameraDrag(change, sample, afterContactChange = true),
            current,
          )
      }
      else -> {
        val single = newPress(input, input.pressed.single())
        contact = single
        startLongPress(single)
      }
    }
  }

  private fun onTimeout(press: Press) {
    val single = contact as? Single ?: return
    if (single.press !== press || press.tap != Press.Tap.Pending || effects.gestureInProgress)
      return
    effects.acceptGesture()
    press.stopClick()
    press.tap = Press.Tap.LongPress
    pairing.discard(emitClick = false)
    effects.emitTap(
      TapFamily.LongPress,
      single.sample.copy(
        uptimeMillis = press.startedAtMillis + longClickTimeoutMillis,
        position = target.positionFromScreenLocation(single.sample.screenOffset),
      ),
    )
  }

  private fun Input.Sample.at(offset: Offset, types: Set<PointerType>): GesturePointerSample =
    metadata.copy(screenOffset = offset.toLogicalDpOffset(density), pointerTypes = types)

  private fun newPress(input: Input.Sample, change: PointerInputChange): Single {
    val sample = input.at(change.position, setOf(change.type))
    val secondary = change.type == PointerType.Mouse && PointerButton.Secondary in sample.buttons
    val tapDemand = TapFamily.entries.filterTo(mutableSetOf()) { effects.hasTapDemand(it, sample) }
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
        tap = if (clickDemand) Press.Tap.Pending else Press.Tap.Cancelled,
      )
    // Click candidates claim their press, including mouse clicks competing with a parent click.
    if (clickDemand || TapFamily.TwoFingerTap in tapDemand) change.consume()
    return Single(input.ids, sample, press, drag)
  }

  private fun startLongPress(single: Single) {
    val press = checkNotNull(single.press)
    if (
      TapFamily.LongPress in press.tapDemand &&
        press.type != PointerType.Mouse &&
        single.drag !is SingleDrag.QuickZoom
    ) {
      press.longClickJob = scope.launch {
        delay(longClickTimeoutMillis)
        transition(Input.Timeout(press))
      }
    }
  }

  private fun quickZoomMatches(sample: GesturePointerSample): Boolean =
    options.camera.settings.zoom.enabled && options.bindings.tapDrag.matches(sample)

  private fun onSingleMove(single: Single, input: Input.Sample, change: PointerInputChange) {
    val sample = input.at(change.position, setOf(change.type))
    val oldSample = single.sample
    single.sample = sample
    // A mouse button change selects the drag again from the buttons and modifiers now held.
    // Modifiers alone do not, as in MapLibre GL JS. The replaced drag ends as if released, and
    // the replacement rebases at this event so it cannot apply the previous drag's movement.
    if (change.type == PointerType.Mouse && oldSample.buttons != sample.buttons) {
      val next = drags.cameraDrag(change, sample)
      if (next?.action != single.drag?.action) {
        single.press?.stopClick()
        effects.releaseDrag(single.drag, sample, single.pending)
        single.pending = null
        effects.endDrag()
        single.drag = next
        return
      }
    }

    val press = single.press
    if (
      press != null &&
        (change.position - press.origin).getDistance() >
          (if (press.type == PointerType.Mouse) clickSlopPx else touchSlopPx)
    )
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
      single.twoFingerTap = null
      // A replacement only takes over the camera components it controls.
      single.pending = single.pending?.without(drag.components)
      effects.startDrag(drag)
      pairing.discard(emitClick = drag !is SingleDrag.QuickZoom)
    }

    effects.updateDrag(drag, motion.delta, change, sample)
  }

  private fun selectPair(input: Input.Sample): Pair<PointerInputChange, PointerInputChange> {
    val pressed = input.pressed
    val selected = (contact as? PairContact)?.gesture
    val first = pressed.firstOrNull { it.id == selected?.firstId }
    val second = pressed.firstOrNull { it.id == selected?.secondId }
    if (selected?.hasDemand == true && first != null && second != null) return first to second

    // Keep an admitted pair stable. Search again only if it loses a finger or has no binding.
    for (i in 0 until pressed.lastIndex) {
      for (j in i + 1 until pressed.size) {
        val a = pressed[i]
        val b = pressed[j]
        val sample = input.at((a.position + b.position) / 2f, setOf(a.type, b.type))
        if (
          options.bindings.transform.hasDemand(sample, options.camera.settings) ||
            effects.hasTapDemand(TapFamily.TwoFingerTap, sample)
        )
          return a to b
      }
    }
    return pressed[0] to pressed[1]
  }

  private fun twoFingerTapCandidate(
    input: Input.Sample,
    first: PointerInputChange,
    second: PointerInputChange,
    pressedAtMillis: Long?,
  ): TwoFingerTapCandidate? {
    if (first.type == PointerType.Mouse || second.type == PointerType.Mouse) return null
    val sample = input.at((first.position + second.position) / 2f, setOf(first.type, second.type))
    if (!effects.hasTapDemand(TapFamily.TwoFingerTap, sample)) return null
    return TwoFingerTapCandidate(
      min(pressedAtMillis ?: sample.uptimeMillis, sample.uptimeMillis),
      first.id,
      second.id,
      first.position,
      second.position,
      sample.pointerTypes,
    )
  }

  private fun onRelease(released: Active, input: Input.Sample) {
    val single = released as? Single
    val completedTwoFingerTap = released.twoFingerTap

    if (released is PairContact) released.stage(released.gesture.end())
    effects.releaseDrag(single?.drag, input.metadata, released.pending)

    val press = single?.press
    val click = press?.takeIf { it.tap == Press.Tap.Pending }
    press?.stopClick()
    // A paired press that ended without a click or drag claimed the first tap; drop it.
    if (press?.role == TapPairing.Press.Paired && click == null) pairing.discard(emitClick = false)

    if (effects.gestureInProgress) {
      if (press?.tap == Press.Tap.LongPress) input.consume()
      effects.endDrag()
      return
    }
    when {
      completedTwoFingerTap != null -> {
        if (options.bindings.twoFingerTap.enabled) input.consume()
        effects.acceptGesture()
        effects.emitTap(
          TapFamily.TwoFingerTap,
          input.at(completedTwoFingerTap.centroid, completedTwoFingerTap.pointerTypes).let {
            it.copy(position = target.positionFromScreenLocation(it.screenOffset))
          },
        )
      }
      click != null -> {
        input.consume()
        if (click.role != TapPairing.Press.Bounce) {
          effects.acceptGesture()
          onClick(input.at(click.origin, setOf(click.type)), click, checkNotNull(single).sample)
        }
      }
      press?.tap == Press.Tap.LongPress -> input.consume()
    }
  }

  private fun onClick(
    sample: GesturePointerSample,
    click: Press,
    pressSample: GesturePointerSample,
  ) {
    // Release no longer reports the button, but a secondary click must retain its press metadata.
    val clickSample =
      sample.copy(
        buttons = pressSample.buttons,
        position = target.positionFromScreenLocation(sample.screenOffset),
      )
    val demand = click.tapDemand
    if (click.secondary) {
      if (TapFamily.SecondaryClick in demand) effects.emitTap(TapFamily.SecondaryClick, clickSample)
      pairing.discard(emitClick = false)
      return
    }

    if (click.pairing.doubleTap) {
      effects.emitTap(TapFamily.DoubleTap, clickSample)
      pairing.discard(emitClick = false)
      return
    }

    val doubleTap = TapFamily.DoubleTap in demand
    val mouse = click.type == PointerType.Mouse
    // A touch tap waits to see whether a second tap uses it.
    if (TapFamily.Tap in demand && (mouse || (!doubleTap && !click.quickZoom)))
      effects.emitTap(TapFamily.Tap, clickSample)
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

  /** One contact that may still end as a tap, click, or long press. Physical pixels. */
  private class Press(
    val origin: Offset,
    val type: PointerType,
    val secondary: Boolean,
    val startedAtMillis: Long,
    val pairing: TapPairing.Pairing,
    val tapDemand: Set<TapFamily>,
    val quickZoom: Boolean,
    var tap: Tap,
  ) {
    var longClickJob: Job? = null

    enum class Tap {
      Pending,
      Cancelled,
      LongPress,
    }

    val role: TapPairing.Press
      get() = pairing.role

    fun stopClick() {
      if (tap != Tap.LongPress) tap = Tap.Cancelled
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

    fun update(input: Input.Sample, slopPixels: Float): Boolean {
      val now = input.metadata.uptimeMillis
      if (now - startedAtMillis > GestureMath.TWO_FINGER_TAP_TIMEOUT_MILLIS) {
        return false
      }
      input.event.changes.forEach { change ->
        when (change.id) {
          firstId -> firstCurrent = change.position
          secondId -> secondCurrent = change.position
        }
      }
      return (firstCurrent - firstOrigin).getDistance() <= slopPixels &&
        (secondCurrent - secondOrigin).getDistance() <= slopPixels
    }
  }

  /** Camera sessions, focus, tap delivery, and continuation effects. */
  private inner class Effects {
    private val gestureToken: CameraInputToken?
      get() = cameraSession?.token

    val gestureInProgress: Boolean
      get() = gestureToken != null

    val authorityRevoked: Boolean
      get() = gestureToken?.acceptsCommands == false

    private var cameraSession: GestureInputSession? = null

    fun acceptGesture() {
      if (!target.isGestureReady) return
      onRecognizedGesture()
      runCatching { focusRequester.requestFocus() }
      focus.engage(byKey = false)
      if (gestureToken == null) target.interruptCamera() else target.observeInput()
    }

    private fun retainCameraAuthority(): Boolean {
      if (gestureToken?.acceptsCommands == true) return true
      revoke()
      return false
    }

    fun pair(
      input: Input.Sample,
      first: PointerInputChange,
      second: PointerInputChange,
      onRecognized: (CameraComponent) -> Unit,
    ) =
      PointerPairGesture(
        target,
        options,
        density,
        input.at((first.position + second.position) / 2f, setOf(first.type, second.type)),
        first,
        second,
        begin = ::beginGesture,
        onRecognized = onRecognized,
        retainAuthority = ::retainCameraAuthority,
        maximumFlingVelocity = maximumFlingVelocity,
        touchSlopPx = touchSlopPx,
      )

    fun startDrag(drag: SingleDrag) {
      beginGesture()
      drag.components.forEach { gestureToken?.rearm(it) }
    }

    fun updateDrag(
      drag: SingleDrag,
      delta: Offset,
      change: PointerInputChange,
      sample: GesturePointerSample,
    ) {
      if (!retainCameraAuthority()) return
      drag.update(delta, change, sample, gestureToken)
      change.consume()
    }

    /** Runs a started drag's release and the momentum staged for the camera session. */
    fun releaseDrag(
      drag: SingleDrag?,
      sample: GesturePointerSample,
      pending: PointerContinuation?,
    ) {
      val completed = drag?.takeIf { it.active }
      completed?.release(sample, cameraSession)
      val momentum = completed?.takeIf { gestureInProgress }?.momentum(sample)
      val continuation = momentum?.withPrevious(pending) ?: pending
      continuation?.settled()?.let(::animateContinuation)
    }

    fun emitTap(
      family: TapFamily,
      sample: GesturePointerSample,
      generation: Long = target.inputGeneration,
    ) {
      val binding = family.binding(options)
      val action = binding.select(sample, options.camera.settings)

      taps.dispatch(family, sample) camera@{
        val direction =
          when (action) {
            CameraAction.ZoomIn -> 1.0
            CameraAction.ZoomOut -> -1.0
            InputAction.None,
            UnspecifiedAction,
            null -> return@camera
          }
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

    fun hasTapDemand(family: TapFamily, sample: GesturePointerSample): Boolean =
      family.matches(options, sample) &&
        (taps.hasHandlers(family) ||
          family.binding(options).select(sample, options.camera.settings)?.let {
            it != InputAction.None && it != UnspecifiedAction
          } == true)

    private fun animateContinuation(velocity: PointerContinuation) {
      val session = checkNotNull(cameraSession)
      val token = session.token
      velocity.pan?.let { fling -> session.scope.launch { target.animateFling(fling, token) } }
      velocity.scale?.let { scale ->
        session.scope.launch {
          animateDecelerating(scale.duration) { fraction ->
            val delta = scale.zoomDelta * fraction
            if (delta != 0.0)
              target.inputScaleBy(
                zoomLevelsToScale(delta),
                velocity.scaleAnchor,
                gestureToken = token,
              )
          }
        }
      }
      velocity.rotation?.let { rotation ->
        session.scope.launch {
          animateDecelerating(rotation.duration) { fraction ->
            target.inputRotateAndPitchBy(
              rotation.bearingDelta * fraction,
              0.0,
              feedback = false,
              anchor = velocity.rotationAnchor,
              gestureToken = token,
            )
          }
        }
      }
      velocity.pitch?.let { pitch ->
        session.scope.launch {
          animateDecelerating(pitch.duration) { fraction ->
            target.inputRotateAndPitchBy(0.0, pitch.pitchDelta * fraction, gestureToken = token)
          }
        }
      }
    }

    fun endDrag() {
      val session = cameraSession ?: return
      cameraSession = null
      session.end()
    }

    fun cancelCameraSession() {
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
            revoke()
          }
        }
      cameraSession = session
      return token
    }
  }
}
