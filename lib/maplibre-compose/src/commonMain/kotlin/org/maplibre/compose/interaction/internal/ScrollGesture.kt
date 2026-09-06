package org.maplibre.compose.interaction.internal

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.maplibre.compose.camera.internal.CameraInputTarget
import org.maplibre.compose.camera.internal.inputPanBy
import org.maplibre.compose.camera.internal.inputScaleBy
import org.maplibre.compose.interaction.CameraInputOrigin
import org.maplibre.compose.interaction.GestureCancellationReason
import org.maplibre.compose.interaction.MapInteractions
import org.maplibre.compose.interaction.ScreenVelocity
import org.maplibre.compose.interaction.ScrollEvent

/** Scroll shares the pointer arena so it sees consumption before claiming an event. */
internal class ScrollGesture(
  private val target: CameraInputTarget,
  private val options: MapInteractions,
  private val currentOptions: () -> MapInteractions,
  private val subscriptions: InteractionSubscriptions,
  private val ids: GestureIds,
  private val density: Density,
  private val viewportSize: () -> IntSize,
  private val scrollConverter: ScrollConverter,
  private val scope: CoroutineScope,
) {
  private class Burst(
    val response: ScrollResponse,
    val membership: LifecycleMembership,
    val session: GestureInputSession,
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

    val normalized =
      normalizeScroll(scrollConverter(event, density, viewportSize()), density) ?: return
    val sample = event.gestureSample(burst?.sample?.gestureId ?: ids.next(), target, density)
    val previous = burst
    if (previous != null && sample.uptimeMillis < previous.sample.uptimeMillis) {
      previous.velocity.resetTracking()
      return
    }

    if (
      previous != null &&
        (!previous.token.acceptsCommands || previous.sample.buttons != sample.buttons)
    ) {
      cancel(
        if (previous.token.acceptsCommands) GestureCancellationReason.BindingChanged
        else GestureCancellationReason.CameraTakeover
      )
    }

    val selected =
      options.bindings.scroll.select(sample, options.camera)?.takeUnless {
        it == ScrollResponse.None
      }
    if (burst != null && burst?.response != selected)
      cancel(GestureCancellationReason.BindingChanged)
    if (selected == null) return
    if (selected == ScrollResponse.Zoom && normalized.y.value == 0f) return

    target.observeInput()
    val current =
      burst
        ?: run {
          takeOverContacts()
          lateinit var session: GestureInputSession
          session =
            GestureInputSession(scope, target, origin = CameraInputOrigin.Scroll) {
              if (burst?.session === session)
                cancel(
                  if (target.isGestureReady) GestureCancellationReason.CameraTakeover
                  else GestureCancellationReason.Detached
                )
            }
          Burst(
              selected,
              subscriptions.scroll.capture(),
              session,
              sample.copy(gestureId = ids.next()),
            )
            .also {
              burst = it
              it.membership.observe(
                ScrollEvent.Start(it.sample, it.sample.screenOffset),
                currentOptions().bindings.scroll.handlers,
              )
            }
        }

    if (!current.token.acceptsCommands) {
      cancel(GestureCancellationReason.CameraTakeover)
      return
    }

    current.sample = sample.copy(gestureId = current.sample.gestureId)
    current.displacement += Offset(normalized.x.value, normalized.y.value)
    current.velocity.addPosition(sample.uptimeMillis, current.displacement)
    current.membership.observe(
      ScrollEvent.Delta(current.sample, normalized),
      currentOptions().bindings.scroll.handlers,
    )

    if (!current.token.acceptsCommands) {
      cancel(GestureCancellationReason.CameraTakeover)
      return
    }

    when (selected) {
      ScrollResponse.Pan ->
        target.inputPanBy(
          normalized.x.value.toDouble(),
          normalized.y.value.toDouble(),
          gestureToken = current.token,
        )
      ScrollResponse.Zoom -> {
        val scale =
          zoomLevelsToScale(normalized.y.value.toDouble() * options.bindings.scroll.zoomPerDp)
        if (scale.isFinite() && scale > 0.0)
          target.inputScaleBy(
            scale,
            options.bindings.scroll.anchor.location(current.sample),
            gestureToken = current.token,
          )
      }
      else -> Unit
    }

    event.changes.forEach { it.consume() }

    // Wheel events have no release. The idle timeout closes one burst without adding momentum;
    // the host may already include its own inertial scrolling.
    finishJob?.cancel()
    finishJob =
      current.session.scope.launch {
        delay(options.bindings.scroll.idleDuration.inWholeMilliseconds)
        burst = null
        finishJob = null
        val velocity = current.velocity.calculateVelocity(pointerInput = false)
        try {
          current.membership.observe(
            ScrollEvent.End(
              current.sample,
              ScreenVelocity(velocity.x.toDouble(), velocity.y.toDouble()),
            ),
            currentOptions().bindings.scroll.handlers,
          )
        } finally {
          current.session.end()
        }
      }
  }

  fun cancel(reason: GestureCancellationReason = GestureCancellationReason.InputCancelled) {
    finishJob?.cancel()
    finishJob = null
    val previous = burst ?: return
    burst = null
    try {
      previous.membership.observe(
        ScrollEvent.Cancel(previous.sample, reason),
        currentOptions().bindings.scroll.handlers,
      )
    } finally {
      previous.session.cancel()
    }
  }
}
