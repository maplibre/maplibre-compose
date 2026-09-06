package org.maplibre.compose.interaction.internal

import androidx.compose.foundation.Indication
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.focusable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import org.jetbrains.compose.resources.stringResource
import org.maplibre.compose.camera.internal.CameraInputTarget
import org.maplibre.compose.generated.Res
import org.maplibre.compose.generated.map
import org.maplibre.compose.generated.map_engaged
import org.maplibre.compose.generated.map_not_engaged
import org.maplibre.compose.interaction.GestureCancellationReason
import org.maplibre.compose.interaction.MapInteractions

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
  target: CameraInputTarget,
  captureClickPath: (TapFamily) -> ClickPath?,
  options: MapInteractions,
  density: Density,
  focusRequester: FocusRequester,
  focus: InputFocus,
  environment: InputEnvironment,
  continuation: GestureContinuation,
  rotaryNotchPixels: Float,
  subscriptions: InteractionSubscriptions,
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
      RotaryGesture(
        target,
        {
          currentOptions.value.let { latest ->
            latest.bindings.rotary.copy(
              enabled = latest.bindings.rotary.enabled && latest.camera.zoom.enabled
            )
          }
        },
        ids,
        rotaryNotchPixels,
        inputScope,
        continuation,
        subscriptions.rotary,
      )
    }
  DisposableEffect(rotaryInput) { onDispose { rotaryInput.cancel() } }

  val keyInput =
    remember(target, focus, continuation) {
      KeyInput(
        target,
        { currentOptions.value },
        focus,
        continuation,
        ids,
        inputScope,
        subscriptions.keys,
      )
    }
  DisposableEffect(keyInput) { onDispose { keyInput.cancel() } }

  SideEffect {
    subscriptions.update(options)
    keyInput.configure(options.structuralKey)
  }

  SideEffect { continuation.configure(options.structuralKey, target) }

  val keys = options.bindings.keys.hasCameraBindings(options.camera)
  val rotary =
    options.bindings.rotary.enabled &&
      options.camera.zoom.enabled &&
      rotaryNotchPixels > 0f &&
      rotaryNotchPixels.isFinite()
  focus.hasKeyBindings = keys

  return this.semantics {
      contentDescription = environment.contentDescription
      stateDescription = if (engaged) environment.engaged else environment.notEngaged
    }
    // Key and rotary events reach the focused node, so these precede the focus target in the chain.
    .onKeyEvent(keyInput::onEvent)
    .onRotaryScrollEvent(rotaryInput::onEvent)
    .onFocusChanged {
      focus.onFocusChanged(it.isFocused)
      if (!it.isFocused) {
        rotaryInput.cancel()
        keyInput.cancel()
      }
    }
    .focusRequester(focusRequester)
    .focusable(enabled = keys || rotary || focus.claimedKeys.isNotEmpty())
    .drawBoxZoom(boxZoom)
    .pointerGestures(
      target,
      captureClickPath,
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
      subscriptions,
      rememberScrollConverter(),
    )
}

/** The composition locals that one [mapInput] node reads, resolved where the node is composed. */
internal class InputEnvironment(
  val contentDescription: String,
  val engaged: String,
  val notEngaged: String,
  val indication: Indication?,
)

@Composable
internal fun inputEnvironment(): InputEnvironment =
  InputEnvironment(
    contentDescription = stringResource(Res.string.map),
    engaged = stringResource(Res.string.map_engaged),
    notEngaged = stringResource(Res.string.map_not_engaged),
    indication = LocalIndication.current,
  )

private fun Modifier.pointerGestures(
  target: CameraInputTarget,
  captureClickPath: (TapFamily) -> ClickPath?,
  options: MapInteractions,
  currentOptions: () -> MapInteractions,
  currentStructuralKey: () -> Any,
  density: Density,
  focusRequester: FocusRequester,
  focus: InputFocus,
  continuation: GestureContinuation,
  ids: GestureIds,
  boxZoom: BoxZoomPreview,
  platformRouting: PlatformTransformRouting,
  subscriptions: InteractionSubscriptions,
  scrollConverter: ScrollConverter,
): Modifier =
  pointerInput(target, options.structuralKey, density, continuation, scrollConverter) {
    val scope = CoroutineScope(currentCoroutineContext())
    val hover =
      HoverGesture(
        scope,
        target::positionFromScreenLocation,
        currentOptions,
        ids,
        density,
        subscriptions,
      )
    val scroll =
      ScrollGesture(
        target,
        options,
        currentOptions,
        subscriptions,
        ids,
        density,
        { size },
        scrollConverter,
        scope,
        continuation,
      )

    lateinit var platform: PlatformTransformSession
    var platformRouteActive = false
    val gesture =
      PointerGesture(
        target = target,
        taps = TapDispatcher(scope, captureClickPath, subscriptions, currentOptions),
        options = options,
        currentOptions = currentOptions,
        subscriptions = subscriptions,
        ids = ids,
        boxZoom = boxZoom,
        density = density,
        focusRequester = focusRequester,
        focus = focus,
        viewportSize = { size },
        clickSlopPx = 3.dp.toPx(),
        panSlopPx = GestureMath.PAN_START_DP.dp.toPx(),
        touchSlopPx = viewConfiguration.touchSlop,
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
      PlatformTransformSession(
        target,
        options,
        currentOptions,
        subscriptions,
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
            platformRouting.route(
              event.type,
              hasAndroidTransformClassification(event),
              event.changes,
            )

          // Host transforms and raw contacts are exclusive: wrapper contacts must never also
          // become a map drag or tap. Scroll competes in the same Compose consumption pass.
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

          // A parent can consume later in Main. Recheck in Final before continuing the session.
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

/** Compose reports physical pixels; MapLibre projects in logical ones. */
internal fun Offset.toLogicalDpOffset(density: Density): DpOffset =
  DpOffset((x / density.density).dp, (y / density.density).dp)
