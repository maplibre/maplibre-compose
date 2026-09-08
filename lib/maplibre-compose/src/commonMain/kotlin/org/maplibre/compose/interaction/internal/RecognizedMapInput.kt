package org.maplibre.compose.interaction.internal

import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.isSpecified
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.maplibre.compose.camera.internal.CameraInputTarget
import org.maplibre.compose.camera.internal.CameraInputToken
import org.maplibre.compose.camera.internal.inputPanBy
import org.maplibre.compose.camera.internal.inputScaleBy
import org.maplibre.compose.interaction.MapInteractions

/**
 * Applies gestures that a host recognized itself, in logical pixels, under the map's camera
 * authority. Each call is one complete gesture; a fling continues until its animation ends or newer
 * input cancels it.
 */
internal class RecognizedMapInput(
  private val target: CameraInputTarget,
  captureClickPath: (TapFamily) -> ClickPath?,
  hasClickHandlers: (TapFamily) -> Boolean,
  private val currentInteractions: () -> MapInteractions,
  private val scope: CoroutineScope,
) {
  private var session: GestureInputSession? = null
  private var clickGeneration = 0L
  private val taps =
    TapDispatcher(
      scope,
      { family ->
        val generation = clickGeneration
        captureClickPath(family)?.let { path ->
          ClickPath({ generation == clickGeneration && path.isValid() }, path.deliver)
        }
      },
      hasClickHandlers,
      // A host that recognizes its own gestures has no Compose bindings; taps take the standard
      // ones.
      { InputConfiguration(currentInteractions(), InteractionBindings.standard()) },
    )
  private val startedAt = TimeSource.Monotonic.markNow()

  fun pan(delta: DpOffset) {
    delta.requireFinite()
    if (delta == DpOffset.Zero) return
    discreteGesture {
      target.inputPanBy(delta.x.value.toDouble(), delta.y.value.toDouble(), gestureToken = it)
    }
  }

  fun scale(factor: Double, anchor: DpOffset?) {
    require(factor.isFinite() && factor > 0.0) { "Scale must be finite and positive" }
    anchor?.requireFinite()
    if (factor == 1.0) return
    discreteGesture { target.inputScaleBy(factor, anchor, gestureToken = it) }
  }

  fun fling(velocity: DpOffset) {
    velocity.requireFinite()
    cancelCamera()
    target.observeInput()
    target.interruptCamera()
    val tuning = currentInteractions().camera.settings.pan.momentum.takeIf { it.enabled } ?: return
    val fling =
      GestureMath.fling(velocity.x.value.toDouble(), velocity.y.value.toDouble(), tuning) ?: return
    val active = GestureInputSession(scope, target)
    session = active
    active.scope.launch { target.animateFling(fling, active.token) }
    active.end()
  }

  fun click(offset: DpOffset) {
    offset.requireFinite()
    cancelCamera()
    target.observeInput()
    target.interruptCamera()
    taps.dispatch(
      TapFamily.Tap,
      GesturePointerSample(
        startedAt.elapsedNow().inWholeMilliseconds,
        offset,
        target.positionFromScreenLocation(offset),
        setOf(PointerType.Touch),
        emptySet(),
        emptySet(),
      ),
    ) {}
  }

  /** Revokes queued camera work and pending clicks when the presentation goes away. */
  fun cancel() {
    clickGeneration++
    cancelCamera()
  }

  private fun cancelCamera() {
    session?.cancel()
    session = null
  }

  private inline fun discreteGesture(command: (CameraInputToken) -> Unit) {
    cancelCamera()
    target.observeInput()
    target.interruptCamera()
    val active = GestureInputSession(scope, target)
    session = active
    try {
      command(active.token)
      active.end()
    } catch (error: Throwable) {
      active.cancel()
      throw error
    }
  }
}

private fun DpOffset.requireFinite() {
  require(isSpecified && x.value.isFinite() && y.value.isFinite()) {
    "Input coordinates must be finite"
  }
}
