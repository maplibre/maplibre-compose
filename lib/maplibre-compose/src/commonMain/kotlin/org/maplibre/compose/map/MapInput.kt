package org.maplibre.compose.map

import androidx.compose.foundation.Indication
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.pow
import kotlin.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.maplibre.compose.generated.Res
import org.maplibre.compose.generated.map
import org.maplibre.compose.generated.map_engaged
import org.maplibre.compose.generated.map_not_engaged
import org.maplibre.compose.style.scaledBy
import org.maplibre.compose.style.systemAnimatorDurationScale

/**
 * Neither backend owns platform gestures: MapLibre Native declines to, and GL JS is composited
 * under Compose where its own DOM handlers never fire.
 *
 * The map is a focus target while key or rotary bindings need one. Releases of previously claimed
 * keys remain handled when their bindings are disabled. [rotaryNotchPixels] is the scroll distance
 * of one rotary detent; zero disables rotary zoom.
 */
@Composable
internal fun Modifier.mapInput(
  target: GestureTarget,
  clicks: MapInteractionTarget,
  options: MapGestures,
  density: Density,
  focusRequester: FocusRequester,
  focus: MapInputFocus,
  environment: MapInputEnvironment,
  continuation: GestureContinuation,
  rotaryNotchPixels: Float,
): Modifier {
  // The semantics block observes no snapshot state, so engagement is read here.
  val engaged = focus.isEngaged
  val currentOptions = rememberUpdatedState(options)
  val pointerOptions = key(options.structuralKey) { rememberUpdatedState(options) }
  val ids = remember(target) { GestureIds() }
  val boxZoom = remember(target) { BoxZoomPreview() }
  val platformRouting = remember(target) { PlatformTransformRouting() }
  val inputScope = rememberCoroutineScope()
  val rotaryInput =
    remember(target, options.structuralKey, rotaryNotchPixels, continuation) {
      MapRotaryGesture(
        target,
        { currentOptions.value.keyBindings.rotary },
        ids,
        rotaryNotchPixels,
        inputScope,
        continuation,
      )
    }
  DisposableEffect(rotaryInput) { onDispose { rotaryInput.cancel() } }
  SideEffect { continuation.configure(options.structuralKey, target) }
  val keys = options.hasKeyboardGesture
  val rotary =
    options.keyBindings.rotary.enabled && rotaryNotchPixels > 0f && rotaryNotchPixels.isFinite()
  focus.configure(options.structuralKey)
  focus.hasKeyBindings = keys
  return this.semantics {
      contentDescription = environment.contentDescription
      stateDescription = if (engaged) environment.engaged else environment.notEngaged
    }
    // Key and rotary events reach the focused node, so these precede the focus target in the chain.
    .keyboardInput(target, options, focus, continuation, ids)
    .onRotaryScrollEvent(rotaryInput::onEvent)
    .onFocusChanged {
      focus.onFocusChanged(it.isFocused)
      if (!it.isFocused) rotaryInput.cancel()
    }
    .focusRequester(focusRequester)
    .focusable(enabled = keys || rotary || focus.claimedKeys.isNotEmpty())
    .drawBoxZoom(boxZoom)
    .pointerGestures(
      target,
      clicks,
      options,
      { pointerOptions.value },
      { currentOptions.value.structuralKey },
      density,
      focusRequester,
      focus,
      continuation,
      ids,
      boxZoom,
      platformRouting,
    )
}

private val MapGestures.hasKeyboardGesture: Boolean
  get() = keyBindings.hasCameraBindings

/** The composition locals that one [mapInput] node reads, resolved where the node is composed. */
internal class MapInputEnvironment(
  val contentDescription: String,
  val engaged: String,
  val notEngaged: String,
  val indication: Indication?,
)

@Composable
internal fun mapInputEnvironment(): MapInputEnvironment =
  MapInputEnvironment(
    contentDescription = stringResource(Res.string.map),
    engaged = stringResource(Res.string.map_engaged),
    notEngaged = stringResource(Res.string.map_not_engaged),
    indication = LocalIndication.current,
  )

/**
 * The focus and engagement of one [mapInput] node. The node writes both states, and [onChanged]
 * reports each engagement write.
 *
 * A focused node holds Compose focus. An engaged node consumes the keys that pan, zoom, rotate, and
 * tilt. A node that is focused and not engaged passes those keys through, so focus traversal
 * continues from the map.
 */
internal class MapInputFocus(private val onChanged: (engaged: Boolean) -> Unit) {
  /**
   * Focus interactions for the indication the node draws. The map reports focus only while it is a
   * traversal candidate: an engaged map is a mode, and the camera moving under the keys is its
   * indication.
   */
  val indicationInteractions = MutableInteractionSource()

  /** Claimed keys; false retains only consumption until release after configuration changes. */
  val claimedKeys = mutableStateMapOf<Key, Boolean>()
  private var structuralKey: Any? = null

  fun configure(key: Any) {
    if (structuralKey == key) return
    structuralKey = key
    claimedKeys.keys.toList().forEach { claimedKeys[it] = false }
  }

  /** Engagement belongs to the key handler, so a map without one never engages or stays engaged. */
  var hasKeyBindings = false
    set(value) {
      field = value
      if (!value) disengage()
    }

  private var isFocused = false
  private var engagedByKey = false
  private var shownFocus: FocusInteraction.Focus? = null

  var isEngaged: Boolean by mutableStateOf(false)
    private set

  /** Whether Back releases the map. A pointer press engages without claiming Back. */
  val consumesBack: Boolean
    get() = isEngaged && engagedByKey

  fun onFocusChanged(focused: Boolean) {
    isFocused = focused
    if (!focused) {
      disengage()
      claimedKeys.clear()
    }
    showFocus()
  }

  /** Returns false when the node is not focused, because only a focused node engages. */
  fun engage(byKey: Boolean): Boolean {
    if (!isFocused || !hasKeyBindings) return false
    isEngaged = true
    engagedByKey = byKey
    showFocus()
    onChanged(true)
    return true
  }

  /** Returns false when the node was not engaged. */
  fun disengage(): Boolean {
    if (!isEngaged) return false
    isEngaged = false
    showFocus()
    onChanged(false)
    return true
  }

  private fun showFocus() {
    val show = isFocused && !isEngaged
    val shown = shownFocus
    if (show && shown == null) {
      shownFocus = FocusInteraction.Focus().also { indicationInteractions.tryEmit(it) }
    } else if (!show && shown != null) {
      shownFocus = null
      indicationInteractions.tryEmit(FocusInteraction.Unfocus(shown))
    }
  }

  /** Reports the current state again, for a listener that missed earlier writes. */
  fun replay() = onChanged(isEngaged)
}

private fun Modifier.keyboardInput(
  target: GestureTarget,
  options: MapGestures,
  focus: MapInputFocus,
  continuation: GestureContinuation,
  ids: GestureIds,
): Modifier = onKeyEvent { event ->
  if (event.type == KeyEventType.KeyUp)
    return@onKeyEvent focus.claimedKeys.remove(event.key) != null
  if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
  if (focus.claimedKeys[event.key] == false) return@onKeyEvent true
  if (!options.keyBindings.hasCameraBindings) return@onKeyEvent false
  val modifiers = buildSet {
    if (event.isShiftPressed) add(KeyModifier.Shift)
    if (event.isCtrlPressed) add(KeyModifier.Ctrl)
    if (event.isAltPressed) add(KeyModifier.Alt)
    if (event.isMetaPressed) add(KeyModifier.Meta)
  }
  val action =
    options.keyBindings.chords[KeyChord(event.key, *modifiers.toTypedArray())]
      ?: return@onKeyEvent false
  val repeated = event.key in focus.claimedKeys
  val consumed =
    when (action) {
      GestureKeyAction.Engage -> focus.engage(byKey = true) || repeated
      GestureKeyAction.Disengage -> focus.disengage() || repeated
      GestureKeyAction.Back -> (focus.consumesBack && focus.disengage()) || repeated
      else -> focus.isEngaged
    }
  if (!consumed) return@onKeyEvent false
  if (!repeated) focus.claimedKeys[event.key] = true
  target.observeInput()
  val metadata = KeyGestureEvent(ids.next(), inputUptimeMillis(), event.key, modifiers, repeated)
  val keys = options.keyBindings
  if (!action.isCamera) keys.onEvent?.invoke(metadata)
  else {
    continuation.finish(target::cancelGesture)
    target.discreteGesture(continuation, beforeCommand = { keys.onEvent?.invoke(metadata) }) { token
      ->
      val duration = options.scaledAnimationDuration()
      val pan = keys.panStep.value.toDouble()
      when (action) {
        GestureKeyAction.PanLeft -> moveByAwaitingTransition(pan, 0.0, duration, token)
        GestureKeyAction.PanRight -> moveByAwaitingTransition(-pan, 0.0, duration, token)
        GestureKeyAction.PanUp -> moveByAwaitingTransition(0.0, pan, duration, token)
        GestureKeyAction.PanDown -> moveByAwaitingTransition(0.0, -pan, duration, token)
        GestureKeyAction.ZoomIn ->
          scaleByAwaitingTransition(zoomLevelsToScale(keys.zoomStep), null, duration, token)
        GestureKeyAction.ZoomOut ->
          scaleByAwaitingTransition(zoomLevelsToScale(-keys.zoomStep), null, duration, token)
        GestureKeyAction.RotateLeft ->
          rotateAndPitchByAwaitingTransition(-keys.rotateStep, 0.0, duration, token)
        GestureKeyAction.RotateRight ->
          rotateAndPitchByAwaitingTransition(keys.rotateStep, 0.0, duration, token)
        GestureKeyAction.TiltUp ->
          rotateAndPitchByAwaitingTransition(0.0, keys.pitchStep, duration, token)
        GestureKeyAction.TiltDown ->
          rotateAndPitchByAwaitingTransition(0.0, -keys.pitchStep, duration, token)
        else -> Unit
      }
    }
  }
  true
}

private fun Modifier.pointerGestures(
  target: GestureTarget,
  clicks: MapInteractionTarget,
  options: MapGestures,
  currentOptions: () -> MapGestures,
  currentStructuralKey: () -> Any,
  density: Density,
  focusRequester: FocusRequester,
  focus: MapInputFocus,
  continuation: GestureContinuation,
  ids: GestureIds,
  boxZoom: BoxZoomPreview,
  platformRouting: PlatformTransformRouting,
): Modifier =
  pointerInput(target, options.structuralKey, density, continuation) {
    val scope = CoroutineScope(currentCoroutineContext())
    val hover = MapHoverGesture(scope, target, clicks, currentOptions, ids, density)
    val scroll =
      MapScrollGesture(target, options, currentOptions, ids, density, { size }, scope, continuation)
    lateinit var platform: MapPlatformTransform
    var platformRouteActive = false
    val gesture =
      MapPointerGesture(
        target = target,
        clicks = clicks,
        taps = MapTapDispatcher(scope, clicks, currentOptions),
        options = options,
        currentOptions = currentOptions,
        ids = ids,
        boxZoom = boxZoom,
        density = density,
        focusRequester = focusRequester,
        focus = focus,
        viewportSize = { size },
        clickSlopPx = 3.dp.toPx(),
        panSlopPx = GestureMath.PAN_START_DP.dp.toPx(),
        twoFingerTapSlopPx = GestureMath.TWO_FINGER_TAP_SLOP_DP.dp.toPx(),
        doubleTapSlopPx = GestureMath.DOUBLE_TAP_SLOP_DP.dp.toPx(),
        doubleClickMinTimeMillis = viewConfiguration.doubleTapMinTimeMillis,
        doubleClickTimeoutMillis = viewConfiguration.doubleTapTimeoutMillis,
        longClickTimeoutMillis = viewConfiguration.longPressTimeoutMillis,
        scope = scope,
        continuation = continuation,
        onAcceptedPress = {
          scroll.cancel(GestureCancellationReason.CameraTakeover)
          platform.cancel(GestureCancellationReason.CameraTakeover)
          platformRouteActive = false
        },
      )
    val consumption = PointerInputConsumption {
      gesture.cancel(GestureCancellationReason.InputConsumed)
    }
    platform =
      MapPlatformTransform(
        target,
        options,
        currentOptions,
        ids,
        scope,
        platformRouting,
      ) {
        scroll.cancel(GestureCancellationReason.CameraTakeover)
        continuation.finish(target::cancelGesture)
        runCatching { focusRequester.requestFocus() }
        focus.engage(byKey = false)
      }
    try {
      awaitPointerEventScope {
        while (true) {
          val event = awaitPointerEvent(PointerEventPass.Main)
          val routed =
            platformRouting.route(event.type, isClassifiedPlatformTransform(event), event.changes)
          var claimedPlatform = false
          if (routed) {
            hover.exit()
            if (!platformRouteActive) {
              gesture.cancel(GestureCancellationReason.BindingChanged)
              consumption.suppress()
              platformRouteActive = true
            }
            consumption.main(event) {}
            if (event.changes.any { it.isConsumed }) platformRouting.intercept()
            val change =
              event.changes.firstOrNull { it.scaleFactor != 1f || it.panOffset != Offset.Zero }
                ?: event.changes.firstOrNull()
            if (change != null) {
              claimedPlatform =
                platform.onInput(
                  event.type,
                  event.gestureSample(0, target, density, change.position),
                  change.scaleFactor.toDouble(),
                  change.panOffset.toLogicalDpOffset(density),
                  platformRouting.blocked || event.changes.any { it.isConsumed },
                )
              if (claimedPlatform) event.changes.forEach(PointerInputChange::consume)
            }
            if (
              !platform.isActive &&
                !platformRouting.hasContacts &&
                (event.type == PointerEventType.ScaleEnd ||
                  event.type == PointerEventType.PanEnd ||
                  event.type == PointerEventType.Release)
            )
              platformRouteActive = false
          } else if (event.type == PointerEventType.Scroll) {
            hover.onPointerEvent(event)
            scroll.onPointerEvent(event) {
              platform.cancel(GestureCancellationReason.CameraTakeover)
              platformRouteActive = false
              gesture.cancel(GestureCancellationReason.CameraTakeover)
              consumption.suppress()
            }
          } else {
            if (platform.isActive) hover.exit() else hover.onPointerEvent(event)
            consumption.main(event, gesture::onPointerEvent)
          }
          val final = awaitPointerEvent(PointerEventPass.Final)
          if (routed) {
            if (!claimedPlatform && final.changes.any { it.isConsumed }) {
              platformRouting.intercept()
              platform.cancel(GestureCancellationReason.InputConsumed)
            }
          } else if (event.type != PointerEventType.Scroll) consumption.final(final)
        }
      }
    } finally {
      // MapLibre keeps the gesture flag until it is cleared, so a drag ended by coroutine
      // cancellation rather than by a pointer-up has to clear it here.
      val reason =
        when {
          currentStructuralKey() != options.structuralKey ->
            GestureCancellationReason.ConfigurationChanged
          !target.isGestureReady -> GestureCancellationReason.Detached
          else -> GestureCancellationReason.InputCancelled
        }
      try {
        gesture.cancel(reason)
      } finally {
        try {
          scroll.cancel(reason)
        } finally {
          try {
            platform.cancel(reason)
          } finally {
            hover.exit()
          }
        }
      }
    }
  }

/** Scroll shares the pointer arena so it sees consumption before claiming an event. */
private class MapScrollGesture(
  private val target: GestureTarget,
  private val options: MapGestures,
  private val currentOptions: () -> MapGestures,
  private val ids: GestureIds,
  private val density: Density,
  private val viewportSize: () -> IntSize,
  private val scope: CoroutineScope,
  private val continuation: GestureContinuation,
) {
  private class Burst(
    val binding: GestureBinding,
    val session: GestureInputSession,
    val kind: ScrollKind,
    var sample: GesturePointerSample,
  ) {
    val token
      get() = session.token

    val velocity = GestureVelocityTracker()
    var displacement = Offset.Zero
  }

  private var burst: Burst? = null
  private var finishJob: Job? = null

  fun onPointerEvent(event: PointerEvent, takeOverContacts: () -> Unit) {
    if (event.changes.any { it.isConsumed }) {
      cancel(GestureCancellationReason.InputConsumed)
      return
    }
    val change = event.changes.firstOrNull() ?: return
    val normalized =
      normalizeScroll(change.scrollDelta, scrollUnits(event), density, viewportSize()) ?: return
    val sample = event.gestureSample(burst?.sample?.gestureId ?: ids.next(), target, density)
    val previous = burst
    if (previous != null && sample.uptimeMillis < previous.sample.uptimeMillis) {
      previous.velocity.resetTracking()
      return
    }
    if (
      previous != null &&
        (!previous.token.acceptsCommands ||
          previous.sample.modifierKeys != sample.modifierKeys ||
          previous.sample.buttons != sample.buttons)
    ) {
      cancel(
        if (previous.token.acceptsCommands) GestureCancellationReason.BindingChanged
        else GestureCancellationReason.CameraTakeover
      )
    }
    val kind = burst?.kind ?: normalized.kind
    val selected =
      options.bindings.firstOrNull {
        it.family == GestureFamily.Scroll &&
          kind in it.settings.scrollKinds &&
          it.matches(sample, contact = false)
      } ?: return
    if (burst?.binding?.id != null && burst?.binding?.id != selected.id)
      cancel(GestureCancellationReason.BindingChanged)
    target.observeInput()
    val current =
      burst
        ?: run {
          takeOverContacts()
          continuation.finish(target::cancelGesture)
          lateinit var session: GestureInputSession
          session =
            GestureInputSession(scope, target) {
              if (burst?.session === session)
                cancel(
                  if (target.isGestureReady) GestureCancellationReason.CameraTakeover
                  else GestureCancellationReason.Detached
                )
            }
          Burst(selected, session, kind, sample.copy(gestureId = ids.next())).also {
            burst = it
            handlers(it).observe(ScrollEvent.Start(it.sample, it.sample.screenOffset, kind))
          }
        }
    if (!current.token.acceptsCommands) {
      cancel(GestureCancellationReason.CameraTakeover)
      return
    }
    current.sample = sample.copy(gestureId = current.sample.gestureId)
    current.displacement += Offset(normalized.panDelta.x.value, normalized.panDelta.y.value)
    current.velocity.addPosition(sample.uptimeMillis, current.displacement)
    handlers(current)
      .observe(ScrollEvent.Delta(current.sample, normalized.panDelta, normalized.zoomNotches, kind))
    if (!current.token.acceptsCommands) {
      cancel(GestureCancellationReason.CameraTakeover)
      return
    }
    when (selected.settings.dragAction) {
      DragActionKind.Pan ->
        target.moveBy(
          normalized.panDelta.x.value.toDouble(),
          normalized.panDelta.y.value.toDouble(),
          gestureToken = current.token,
        )
      DragActionKind.Zoom -> {
        val scale = zoomLevelsToScale(-normalized.zoomComponent * selected.settings.zoomStep)
        if (scale.isFinite() && scale > 0.0)
          target.scaleBy(scale, selected.anchor(current.sample), gestureToken = current.token)
      }
      else -> Unit
    }
    change.consume()
    finishJob?.cancel()
    finishJob =
      current.session.scope.launch {
        delay(options.scrollIdleDuration.inWholeMilliseconds)
        burst = null
        finishJob = null
        val velocity = current.velocity.calculateVelocity(pointerInput = false)
        try {
          handlers(current)
            .observe(
              ScrollEvent.End(
                current.sample,
                ScreenVelocity(velocity.x.toDouble(), velocity.y.toDouble()),
                kind,
              )
            )
        } finally {
          current.session.end()
        }
      }
  }

  private fun handlers(burst: Burst): GestureBindingHandlers =
    currentOptions().bindings.firstOrNull { it.id == burst.binding.id }?.handlers
      ?: burst.binding.handlers

  fun cancel(reason: GestureCancellationReason = GestureCancellationReason.InputCancelled) {
    finishJob?.cancel()
    finishJob = null
    val previous = burst ?: return
    burst = null
    try {
      handlers(previous).observe(ScrollEvent.Cancel(previous.sample, reason, previous.kind))
    } finally {
      previous.session.cancel()
    }
  }
}

private class MapPointerGesture(
  private val target: GestureTarget,
  private val clicks: MapInteractionTarget,
  private val taps: MapTapDispatcher,
  private val options: MapGestures,
  private val currentOptions: () -> MapGestures,
  private val ids: GestureIds,
  private val boxZoom: BoxZoomPreview,
  private val density: Density,
  private val focusRequester: FocusRequester,
  private val focus: MapInputFocus,
  private val viewportSize: () -> IntSize,
  private val clickSlopPx: Float,
  private val panSlopPx: Float,
  private val twoFingerTapSlopPx: Float,
  private val doubleTapSlopPx: Float,
  private val doubleClickMinTimeMillis: Long,
  private val doubleClickTimeoutMillis: Long,
  private val longClickTimeoutMillis: Long,
  private val scope: CoroutineScope,
  private val continuation: GestureContinuation,
  private val onAcceptedPress: () -> Unit,
) {
  private var gestureInProgress = false
  private var gestureToken: GestureToken? = null
  private var cameraSession: GestureInputSession? = null
  private var mode = Mode.NONE

  private var selectedDrag: GestureBinding? = null
  private var dragStarted = false
  private var customDragStarted = false
  private var dragSample: GesturePointerSample? = null
  private var suppressedUntilRelease = false
  private var lastSingle: PointerInputChange? = null
  private var singleDragOrigin: Offset? = null
  private var singleMotion = SingleMotion.NONE
  private val singleVelocity = GestureVelocityTracker()

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
  private var lastQuickZoomSpanDeltaPixels = 0.0
  private var longClickJob: Job? = null
  private var longClickHandled = false
  private var tapDemand = emptySet<TapFamily>()
  private var capabilitySnapshot = emptySet<TapFamily>()
  private var secondTapUseful = false
  private var pressInputGeneration = 0L

  /**
   * Pairing state after a first tap. The delayed-click job exists only in [TapWait.Open]; a valid
   * second down moves to [TapWait.Claimed] and cancels that job.
   */
  private var tapWait: TapWait = TapWait.None
  /** What this press is relative to [tapWait]. */
  private var pressRole = PressRole.First

  fun onPointerEvent(event: PointerEvent) {
    // Wheel events have no pressed pointers. Treating one as a release would close the
    // gesture scrollZoom keeps open for the rest of the burst.
    if (event.type == PointerEventType.Scroll) return
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
    if (
      pressed.size >= 2 &&
        (mode == Mode.SINGLE || mode == Mode.QUICK_ZOOM) &&
        selectedDrag?.id?.let { it !in builtInDrags } == true
    ) {
      cancel(GestureCancellationReason.BindingChanged)
      suppressedUntilRelease = true
      return
    }
    updateTwoFingerTap(event, pressed.size)
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
    mode != Mode.NONE ||
      gestureInProgress ||
      lastSingle != null ||
      pair != null ||
      twoFingerTap != null ||
      clickOrigin != null ||
      deferredTwoFingerVelocity != null

  private fun onSingle(event: PointerEvent, change: PointerInputChange) {
    if (pair != null) {
      val completed = pair
      deferredTwoFingerVelocity =
        completed?.end(event.changes.maxOf { it.uptimeMillis }) ?: deferredTwoFingerVelocity
      pair = null
      if (gestureToken?.acceptsCommands == false) {
        retainCameraAuthority()
        return
      }
      mode = Mode.SINGLE
      lastSingle = change
      singleDragOrigin = change.position
      dragSample =
        event.gestureSample(ids.next(), target, density, change.position, setOf(change.type))
      selectedDrag =
        options.binding("dragPan").takeIf { it.matches(checkNotNull(dragSample), contact = true) }
      dragStarted = false
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
    pressedSecondary = change.type == PointerType.Mouse && event.buttons.isSecondaryPressed
    pressedType = change.type
    pressStartedAtMillis = change.uptimeMillis
    longClickHandled = false
    pressRole = classifyPress(change.position, change.uptimeMillis, change.type)
    when (pressRole) {
      PressRole.First -> discardTapWait(emitClick = true)
      PressRole.Paired -> claimOpenTap()
      PressRole.Bounce -> Unit
    }
    val sample =
      event.gestureSample(ids.next(), target, density, change.position, setOf(change.type))
    dragSample = sample
    selectedDrag = selectDrag(sample, paired = pressRole == PressRole.Paired)
    dragStarted = false
    quickZoomCandidate = selectedDrag?.id == "quickZoom"
    if (pressRole == PressRole.First) capabilitySnapshot = clicks.capabilities.toSet()
    tapDemand =
      TapFamily.entries.filterTo(mutableSetOf()) { family ->
        val binding = options.binding(family.bindingId)
        binding.matches(sample, contact = true) &&
          (family in capabilitySnapshot ||
            family.hasHandler(binding.handlers) ||
            binding.settings.tapAction != null)
      }
    secondTapUseful =
      TapFamily.DoubleTap in tapDemand ||
        change.type != PointerType.Mouse &&
          options.binding("quickZoom").matches(sample, contact = true)
    val longPress = TapFamily.LongPress in tapDemand
    val clickDemand = secondTapUseful || tapDemand.any { it != TapFamily.TwoFingerTap }
    if (!clickDemand) clickOrigin = null
    if (
      selectedDrag == null &&
        !clickDemand &&
        listOf("pinchZoom", "twoFingerRotate", "twoFingerTilt", "twoFingerTap").none {
          options.binding(it).matches(sample, contact = true) &&
            (it != "twoFingerTap" || TapFamily.TwoFingerTap in tapDemand)
        }
    )
      return
    quickZoomOriginY = change.position.y
    quickZoomAppliedDelta = 0.0
    lastQuickZoomSpanDeltaPixels = 0.0
    singleMotion = SingleMotion.NONE
    singleVelocity.resetTracking()
    singleVelocity.addPointerInputChange(change)
    deferredTwoFingerVelocity = null
    onAcceptedPress()
    target.observeInput()
    cancelCameraSession()
    continuation.finish(target::cancelGesture)
    runCatching { focusRequester.requestFocus() }
    focus.engage(byKey = false)
    target.cancelTransitions()
    pressInputGeneration = target.inputGeneration

    // Click candidates claim their press, including mouse clicks competing with a parent click.
    if (clickDemand || TapFamily.TwoFingerTap in tapDemand) change.consume()
    if (longPress && change.type != PointerType.Mouse && !quickZoomCandidate) {
      val origin = change.position
      longClickJob = scope.launch {
        delay(longClickTimeoutMillis)
        if (clickOrigin == origin && !gestureInProgress && mode == Mode.SINGLE) {
          longClickHandled = true
          clickOrigin = null
          // This press is a long click, including a paired second tap that was held.
          discardTapWait(emitClick = false)
          continuation.finish(target::onGestureEnded)
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
  }

  private fun selectDrag(sample: GesturePointerSample, paired: Boolean): GestureBinding? =
    options.bindings.firstOrNull { binding ->
      binding.family == GestureFamily.Drag &&
        (binding.id != "quickZoom" || paired && PointerType.Mouse !in sample.pointerTypes) &&
        binding.matches(sample, contact = true) &&
        (currentOptions()
          .bindings
          .firstOrNull { it.id == binding.id }
          ?.handlers
          ?.canStart
          ?.invoke(PointerPressEvent(sample)) != false)
    }

  private fun deliverDrag(event: DragEvent) {
    val binding = selectedDrag ?: return
    val handlers =
      currentOptions().bindings.firstOrNull { it.id == binding.id }?.handlers ?: binding.handlers
    var failure: Throwable? = null
    try {
      handlers.observe(event)
    } catch (cause: Throwable) {
      failure = cause
    }
    if (binding.settings.dragAction == DragActionKind.Custom) {
      val active = failure == null && gestureToken?.acceptsCommands == true
      val response =
        when (event) {
          is DragEvent.Start -> event.takeIf { active }?.also { customDragStarted = true }
          is DragEvent.Delta -> event.takeIf { active && customDragStarted }
          is DragEvent.End ->
            if (!customDragStarted) null
            else {
              customDragStarted = false
              if (active) event
              else
                DragEvent.Cancel(
                  checkNotNull(dragSample),
                  when {
                    failure != null -> GestureCancellationReason.InputCancelled
                    !target.isGestureReady -> GestureCancellationReason.Detached
                    else -> GestureCancellationReason.CameraTakeover
                  },
                )
            }
          is DragEvent.Cancel ->
            event.takeIf { customDragStarted }?.also { customDragStarted = false }
        }
      if (response != null) {
        try {
          handlers.customDrag?.invoke(response)
        } catch (cause: Throwable) {
          if (failure == null) failure = cause else failure.addSuppressed(cause)
        }
      }
    }
    failure?.let { throw it }
  }

  private fun cancelDrag(reason: GestureCancellationReason) {
    if (!dragStarted) return
    dragStarted = false
    try {
      dragSample?.let { deliverDrag(DragEvent.Cancel(it, reason)) }
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
    if (
      change.type == PointerType.Mouse &&
        oldSample != null &&
        (oldSample.buttons != sample.buttons || oldSample.modifierKeys != sample.modifierKeys)
    ) {
      val next = selectDrag(sample, paired = false)
      if (next?.id != selectedDrag?.id) {
        cancelDrag(GestureCancellationReason.BindingChanged)
        cancelCameraSession()
        gestureToken = null
        gestureInProgress = false
        selectedDrag = next
        dragSample = sample.copy(gestureId = ids.next())
        singleDragOrigin = change.position
        singleVelocity.resetTracking()
        singleVelocity.addPointerInputChange(change)
        singleMotion = SingleMotion.NONE
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
    if (change.uptimeMillis < previous.uptimeMillis) {
      singleDragOrigin = change.position
      singleVelocity.resetTracking()
      return
    }
    if (!dragStarted) {
      val origin = singleDragOrigin ?: change.position
      val displacement = change.position - origin
      val slop =
        (if (change.type == PointerType.Mouse) binding.settings.mouseStartSlop
          else binding.settings.startSlop)
          .value * density.density
      if (quickZoomCandidate) {
        if (abs(displacement.y) * 2f < slop) {
          if (abs(displacement.x) > slop) {
            clickOrigin = null
            cancelLongClick()
          }
          return
        }
        val consumedSlop = kotlin.math.sign(displacement.y) * slop / 2f
        quickZoomOriginY += consumedSlop
        delta = Offset(0f, displacement.y - consumedSlop)
      } else {
        val distance = displacement.getDistance()
        if (distance < slop || distance == 0f) return
        delta = displacement * ((distance - slop) / distance)
      }
      clickOrigin = null
      twoFingerTap = null
      deferredTwoFingerVelocity = null
      beginGesture()
      dragStarted = true
      singleMotion =
        when (binding.settings.dragAction) {
          DragActionKind.Pan -> SingleMotion.PAN
          DragActionKind.RotateTilt -> SingleMotion.ROTATE_TILT
          DragActionKind.Zoom -> SingleMotion.QUICK_ZOOM
          else -> SingleMotion.NONE
        }
      discardTapWait(emitClick = !quickZoomCandidate)
      deliverDrag(DragEvent.Start(sample, origin.toLogicalDpOffset(density)))
      if (!retainCameraAuthority()) return
      if (binding.settings.dragAction == DragActionKind.BoxZoom)
        boxZoom.start(origin.toLogicalDpOffset(density), sample.screenOffset)
    }
    singleVelocity.addPointerInputChange(change)
    deliverDrag(DragEvent.Delta(sample, delta.toLogicalDpOffset(density)))
    if (!retainCameraAuthority()) return
    val deltaX = delta.x.toDouble() / density.density
    val deltaY = delta.y.toDouble() / density.density
    when (binding.settings.dragAction) {
      DragActionKind.Pan -> target.moveBy(deltaX, deltaY, gestureToken = gestureToken)
      DragActionKind.RotateTilt ->
        target.rotateAndPitchBy(
          deltaX * binding.settings.bearingDegreesPerDp,
          deltaY * binding.settings.pitchDegreesPerDp,
          anchor = binding.anchor(sample),
          gestureToken = gestureToken,
        )
      DragActionKind.Zoom -> {
        mode = Mode.QUICK_ZOOM
        val direction =
          if (binding.settings.direction == QuickZoomDirection.DownZoomsIn) 1.0 else -1.0
        val targetDelta =
          GestureMath.quickZoomDelta(
            (change.position.y - quickZoomOriginY).toDouble(),
            viewportSize().height.toDouble(),
            binding.settings.maximumZoomChange * direction,
          )
        target.scaleBy(
          zoomLevelsToScale(targetDelta - quickZoomAppliedDelta),
          binding.anchor(sample),
          gestureToken = gestureToken,
        )
        quickZoomAppliedDelta = targetDelta
        lastQuickZoomSpanDeltaPixels = abs(delta.y) * 2.0
      }
      DragActionKind.Custom -> Unit
      DragActionKind.BoxZoom -> boxZoom.move(sample.screenOffset)
    }
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
        if (pairBindingIds.any { options.binding(it).matches(sample, contact = true) })
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
          onAcceptedPress()
          target.observeInput()
        }
        previous.rebase(event, first, second)
      } else previous.move(event, first, second)
      return
    }
    if (previous != null) {
      deferredTwoFingerVelocity =
        previous.end(event.changes.maxOf { it.uptimeMillis }) ?: deferredTwoFingerVelocity
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
      singleMotion = SingleMotion.NONE
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
          TapFamily.TwoFingerTap in tapDemand &&
            options.binding("twoFingerTap").matches(sample, contact = true)
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
    mode = Mode.TWO_FINGER
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
        begin = {
          beginGesture()
          gestureToken
        },
        onRecognized = {
          twoFingerTap = null
          deferredTwoFingerVelocity = null
        },
        retainAuthority = ::retainCameraAuthority,
      )
    pair = candidate
    if (candidate.hasDemand || twoFingerTap != null) {
      if (event.changes.any { it.pressed && !it.previousPressed }) onAcceptedPress()
      target.observeInput()
      pressInputGeneration = target.inputGeneration
      runCatching { focusRequester.requestFocus() }
      focus.engage(byKey = false)
    }
  }

  private fun onRelease(event: PointerEvent) {
    if (dragStarted) {
      dragStarted = false
      val sample = event.gestureSample(checkNotNull(dragSample).gestureId, target, density)
      dragSample = sample
      boxZoom.move(sample.screenOffset)
      val selection = boxZoom.clear()
      val velocity = singleVelocity.calculateVelocity()
      deliverDrag(
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
          continuation.launchBoundsFit(session.scope) {
            target.fitBoundsAwaitingTransition(
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
      completed?.end(event.changes.maxOf { it.uptimeMillis }) ?: deferredTwoFingerVelocity
    pair = null
    if (gestureToken?.acceptsCommands == false) {
      retainCameraAuthority()
      return
    }
    val continuationDuration =
      listOfNotNull(finishSingleVelocity(), pairContinuation?.let(::finishPairVelocity)).maxOrNull()
    deferredTwoFingerVelocity = null
    lastSingle = null
    singleDragOrigin = null
    singleMotion = SingleMotion.NONE
    clickOrigin = null
    longClickHandled = false
    quickZoomCandidate = false
    pressRole = PressRole.First
    twoFingerTap = null
    mode = Mode.NONE
    selectedDrag = null

    if (
      (!gestureInProgress && completedTwoFingerTap != null && options.enabled("twoFingerTap")) ||
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
    generation: Long = pressInputGeneration,
  ) {
    val binding = options.binding(family.bindingId)
    taps.dispatch(family, sample) camera@{
      val action = binding.settings.tapAction ?: return@camera
      var direction = if (action == TapCameraAction.ZoomIn) 1.0 else -1.0
      if (
        family == TapFamily.DoubleTap &&
          PointerType.Mouse in sample.pointerTypes &&
          KeyModifier.Shift in sample.modifierKeys
      )
        direction = -direction
      continuation.launchDiscreteTransition(
        target,
        beforeCommand = {},
        expectedGeneration = generation,
        command = { token ->
          scaleByAwaitingTransition(
            zoomLevelsToScale(direction * binding.settings.zoomStep),
            binding.anchor(sample),
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
      if (TapFamily.LongPress in tapDemand) emitTap(TapFamily.LongPress, clickSample)
      tapWait = TapWait.None
      return
    }
    if (pairedSecondTap && TapFamily.DoubleTap in tapDemand) {
      emitTap(TapFamily.DoubleTap, clickSample)
      tapWait = TapWait.None
      return
    }
    if (TapFamily.Tap in tapDemand && (pressedType == PointerType.Mouse || !awaitsSecondTap()))
      emitTap(TapFamily.Tap, clickSample)
    rememberFirstTap(clickSample, origin, pressedType, sample.uptimeMillis)
  }

  /** Eligibility is fixed at the first press, including subscriber demand. */
  private fun awaitsSecondTap(): Boolean = secondTapUseful

  /** What this down is relative to a [TapWait.Open] first tap. */
  private fun classifyPress(origin: Offset, timeMillis: Long, type: PointerType): PressRole {
    val open = tapWait as? TapWait.Open ?: return PressRole.First
    if (!awaitsSecondTap()) return PressRole.First
    val elapsedMillis = timeMillis - open.tap.upAt
    val samePointerType = type == open.tap.type
    val distancePx = (origin - open.tap.origin).getDistance()
    if (
      isBounceSecondTap(
        elapsedMillis = elapsedMillis,
        distancePx = distancePx,
        samePointerType = samePointerType,
        minTimeMillis = doubleClickMinTimeMillis,
        slopPx = slopPx(),
      )
    ) {
      return PressRole.Bounce
    }
    return if (
      isPairedSecondTap(
        elapsedMillis = elapsedMillis,
        distancePx = distancePx,
        samePointerType = samePointerType,
        minTimeMillis = doubleClickMinTimeMillis,
        timeoutMillis = doubleClickTimeoutMillis,
        slopPx = slopPx(),
      )
    ) {
      PressRole.Paired
    } else {
      PressRole.First
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
    if (!awaitsSecondTap()) {
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
        OpenTap(sample, pressInputGeneration, origin, type, timeMillis, clickOnExpiry, job)
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

  private fun updateTwoFingerTap(event: PointerEvent, pressedCount: Int) {
    val candidate = twoFingerTap ?: return
    if (pressedCount > 2 || !candidate.update(event, twoFingerTapSlopPx)) twoFingerTap = null
  }

  private fun finishSingleVelocity(): Duration? {
    val binding = selectedDrag ?: return null
    val velocity = singleVelocity.calculateVelocity()
    if (!gestureInProgress) return null
    return when (singleMotion) {
      SingleMotion.PAN -> {
        val tuning = binding.settings.fling ?: return null
        val fling =
          GestureMath.fling(
            (velocity.x / density.density).toDouble(),
            (velocity.y / density.density).toDouble(),
            tuning,
          ) ?: return null
        animateFling(fling)
        fling.duration
      }
      SingleMotion.QUICK_ZOOM -> {
        val tuning = binding.settings.velocityContinuation ?: return null
        val direction = if (binding.settings.direction == QuickZoomDirection.DownZoomsIn) 1 else -1
        val velocityResponse =
          GestureMath.scaleVelocity(
            velocity.x.toDouble(),
            velocity.y.toDouble(),
            lastQuickZoomSpanDeltaPixels,
            density.density.toDouble(),
            scalingOut = velocity.y * direction < 0f,
            continuation = tuning,
          ) ?: return null
        animateScaleVelocity(velocityResponse, dragSample?.let(binding::anchor))
        velocityResponse.duration
      }
      SingleMotion.ROTATE_TILT -> {
        val tuning = binding.settings.tiltContinuation ?: return null
        val response =
          GestureMath.tiltVelocity(
            velocity.y / density.density * binding.settings.pitchDegreesPerDp,
            tuning,
          ) ?: return null
        animateTiltVelocity(response)
        response.duration
      }
      else -> null
    }
  }

  private fun finishPairVelocity(velocity: PairContinuation): Duration? {
    velocity.pan?.let(::animateFling)
    velocity.scale?.let { animateScaleVelocity(it, velocity.scaleAnchor) }
    velocity.rotation?.let { animateRotationVelocity(it, velocity.rotationAnchor) }
    velocity.tilt?.let(::animateTiltVelocity)
    return listOfNotNull(
        velocity.pan?.duration,
        velocity.scale?.duration,
        velocity.rotation?.duration,
        velocity.tilt?.duration,
      )
      .maxOrNull()
  }

  /** Interpolates absolute zoom with a decelerate curve. */
  private fun animateScaleVelocity(
    velocity: GestureMath.ScaleVelocity,
    anchor: DpOffset?,
  ) {
    val token = gestureToken
    continuation.launchScale(cameraSession?.scope ?: scope) {
      animateDecelerating(velocity.duration) { frameFraction ->
        val frameZoomDelta = velocity.zoomDelta * frameFraction
        if (frameZoomDelta != 0.0) {
          target.scaleBy(zoomLevelsToScale(frameZoomDelta), anchor, gestureToken = token)
        }
      }
    }
  }

  private fun animateFling(fling: GestureMath.Fling) {
    val token = gestureToken
    continuation.launchFling(cameraSession?.scope ?: scope) {
      animateDecelerating(fling.duration) { frameFraction ->
        val deltaX = fling.offsetXDp * frameFraction
        val deltaY = fling.offsetYDp * frameFraction
        GestureMath.forEachScreenSpaceStep(deltaX, deltaY) { stepX, stepY ->
          target.moveBy(stepX, stepY, gestureToken = token)
        }
      }
    }
  }

  private fun animateTiltVelocity(velocity: GestureMath.TiltVelocity) {
    val token = gestureToken
    continuation.launchRotation(cameraSession?.scope ?: scope) {
      animateDecelerating(velocity.duration) { fraction ->
        target.rotateAndPitchBy(0.0, velocity.pitchDelta * fraction, gestureToken = token)
      }
    }
  }

  /** Remaining motion falls as `(1 - t)^2`. */
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
    velocity: GestureMath.RotationVelocity,
    anchor: DpOffset?,
  ) {
    val token = gestureToken
    continuation.launchRotation(cameraSession?.scope ?: scope) {
      val durationNanos = velocity.duration.inWholeNanoseconds.coerceAtLeast(1L)
      val startedAt = withFrameNanos { it }
      do {
        val now = withFrameNanos { it }
        val progress = ((now - startedAt).toDouble() / durationNanos).coerceIn(0.0, 1.0)
        // Remaining motion falls as (1 - t)^2.
        val frameDelta = velocity.initialDegreesPerFrame * (1.0 - progress).pow(2.0)
        if (frameDelta != 0.0) {
          target.rotateAndPitchBy(frameDelta, 0.0, anchor = anchor, gestureToken = token)
        }
      } while (progress < 1.0)
    }
  }

  private fun endDrag(followUpDuration: Duration) {
    cancelLongClick()
    if (!gestureInProgress) return
    gestureInProgress = false
    val token = gestureToken ?: return
    gestureToken = null
    if (continuation.hasMotionJobs()) {
      // Camera continuations finish when their frame work or awaited engine transition ends.
      continuation.finishWhenMotionJobsComplete(scope, token, ::completeCameraSession)
    } else if (followUpDuration > Duration.ZERO) {
      continuation.finishAfter(scope, followUpDuration, token, ::completeCameraSession)
    } else {
      completeCameraSession(token)
    }
  }

  private fun completeCameraSession(token: GestureToken) {
    val session = cameraSession
    if (session?.token === token) session.end() else target.onGestureEnded(token)
  }

  private fun cancelCameraSession() {
    val previous = cameraSession
    cameraSession = null
    previous?.cancel()
  }

  private fun beginGesture() {
    cancelLongClick()
    if (gestureInProgress) return
    gestureInProgress = true
    lateinit var session: GestureInputSession
    session =
      GestureInputSession(scope, target) {
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
    gestureToken = session.token
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
      gestureInProgress = false
      gestureToken = null
      mode = Mode.NONE
      lastSingle = null
      singleDragOrigin = null
      singleMotion = SingleMotion.NONE
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

  private enum class Mode {
    NONE,
    SINGLE,
    QUICK_ZOOM,
    TWO_FINGER,
  }

  private enum class SingleMotion {
    NONE,
    PAN,
    ROTATE_TILT,
    QUICK_ZOOM,
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

  private companion object {
    val builtInDrags = setOf("dragPan", "dragRotateTilt", "quickZoom", "boxZoom")
    val pairBindingIds =
      listOf("dragPan", "pinchZoom", "twoFingerRotate", "twoFingerTilt", "twoFingerTap")
  }
}

internal class GestureContinuation(private val scope: CoroutineScope) {
  private var configuration: Any? = null

  fun configure(key: Any, target: GestureTarget) {
    if (configuration != null && configuration != key) finish(target::cancelGesture)
    configuration = key
  }

  private var scaleVelocityJob: Job? = null
  private var rotationVelocityJob: Job? = null
  private var flingJob: Job? = null
  private var boundsFitJob: Job? = null
  private var discreteSession: GestureInputSession? = null
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

  fun launchBoundsFit(scope: CoroutineScope, block: suspend CoroutineScope.() -> Unit) {
    boundsFitJob?.cancel()
    boundsFitJob = scope.launch(block = block)
  }

  fun hasMotionJobs(): Boolean =
    boundsFitJob?.isActive == true ||
      flingJob?.isActive == true ||
      scaleVelocityJob?.isActive == true ||
      rotationVelocityJob?.isActive == true

  /**
   * Ends [token] when every motion job finishes on its own. Cancelled work belongs to a revoked
   * session, whose cancellation path closes the token.
   */
  fun finishWhenMotionJobsComplete(
    scope: CoroutineScope,
    token: GestureToken,
    onFinished: (GestureToken) -> Unit,
  ) {
    finishJob?.cancel()
    openToken = token
    finishJob = scope.launch {
      val jobs = listOfNotNull(flingJob, scaleVelocityJob, rotationVelocityJob, boundsFitJob)
      jobs.joinAll()
      if (jobs.any { it.isCancelled }) return@launch
      finishJob = null
      openToken = null
      onFinished(token)
    }
  }

  fun launchDiscreteTransition(
    target: GestureTarget,
    beforeCommand: () -> Unit,
    command: suspend GestureTarget.(GestureToken) -> Unit,
    expectedGeneration: Long? = null,
  ) {
    val token =
      if (expectedGeneration == null) target.onGestureStarted()
      else target.onGestureStartedIfCurrent(expectedGeneration) ?: return
    discreteSession?.cancel()
    val session = GestureInputSession(scope, target, token)
    discreteSession = session
    try {
      beforeCommand()
      if (!session.token.acceptsCommands) {
        session.cancel()
        return
      }
      session.scope.launch {
        try {
          command(target, session.token)
        } finally {
          if (currentCoroutineContext().isActive) session.end() else session.cancel()
        }
      }
    } catch (error: Throwable) {
      session.cancel()
      throw error
    }
  }

  /** Stops this node's earlier continuation and discrete response jobs. */
  fun interrupt() {
    scaleVelocityJob?.cancel()
    scaleVelocityJob = null
    rotationVelocityJob?.cancel()
    rotationVelocityJob = null
    flingJob?.cancel()
    flingJob = null
    boundsFitJob?.cancel()
    boundsFitJob = null
    discreteSession?.cancel()
    discreteSession = null
  }

  private fun takePendingToken(): GestureToken? {
    val token = openToken
    finishJob?.cancel()
    finishJob = null
    openToken = null
    return token?.takeIf { it.acceptsCommands }
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
    takePendingToken()?.let(onFinished)
  }
}

private fun GestureTarget.discreteGesture(
  continuation: GestureContinuation,
  beforeCommand: () -> Unit = {},
  command: suspend GestureTarget.(GestureToken) -> Unit,
) = continuation.launchDiscreteTransition(this, beforeCommand, command)

/** A second down that is too soon and still on the first tap is a bounce. */
internal fun isBounceSecondTap(
  elapsedMillis: Long,
  distancePx: Float,
  samePointerType: Boolean,
  minTimeMillis: Long,
  slopPx: Float,
): Boolean = samePointerType && elapsedMillis < minTimeMillis && distancePx <= slopPx

/**
 * Compose's tap detector pairs a second down to the previous up when the elapsed time is at least
 * [minTimeMillis] and at most [timeoutMillis]. Touch pairing also keeps the two downs within
 * Android's double-tap slop.
 */
internal fun isPairedSecondTap(
  elapsedMillis: Long,
  distancePx: Float,
  samePointerType: Boolean,
  minTimeMillis: Long,
  timeoutMillis: Long,
  slopPx: Float,
): Boolean =
  samePointerType &&
    elapsedMillis >= minTimeMillis &&
    elapsedMillis <= timeoutMillis &&
    distancePx <= slopPx

/** A zoom level is a doubling. */
private fun zoomLevelsToScale(levelDelta: Double): Double = 2.0.pow(levelDelta)

private fun MapGestures.scaledAnimationDuration(): Duration =
  animationDuration.scaledBy(systemAnimatorDurationScale())

/** Compose reports physical pixels; MapLibre projects in logical ones. */
private fun Offset.toLogicalDpOffset(density: Density): DpOffset =
  DpOffset((x / density.density).dp, (y / density.density).dp)
