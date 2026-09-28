package org.maplibre.compose.interaction.internal

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.unit.Density
import org.maplibre.compose.camera.internal.CameraInputTarget

/**
 * Sends each pointer event to host transforms, scroll, or raw contacts. Host transforms and raw
 * contacts are exclusive: wrapper contacts must never also become a map drag or tap. Scroll
 * competes in the same Compose consumption pass.
 */
internal class PointerRouter(
  private val target: CameraInputTarget,
  private val density: Density,
  private val routing: PlatformTransformRouting,
  private val scroll: ScrollGesture,
  private val platform: PlatformTransformSession,
  private val gesture: PointerGesture,
) {
  private enum class Route {
    Platform,
    Scroll,
    Pointer,
  }

  private val consumption = PointerInputConsumption(gesture::cancel, gesture::yieldToOtherHandler)
  private var route = Route.Pointer

  /** The raw gesture was cancelled for a host transform whose contacts have not all lifted. */
  private var platformActive = false
  private var claimedPlatform = false

  fun onMain(event: PointerEvent) {
    val ready = target.isGestureReady
    route =
      when {
        routing.route(event.type, hasAndroidTransformClassification(event), event.changes) ->
          Route.Platform
        event.type == PointerEventType.Scroll -> Route.Scroll
        else -> Route.Pointer
      }
    claimedPlatform = false
    when (route) {
      Route.Platform -> onPlatform(event, ready)
      Route.Scroll if ready -> scroll.onPointerEvent(event, ::onScrollRecognized)
      Route.Scroll -> scroll.cancel()
      Route.Pointer -> consumption.main(event, ready, gesture::onPointerEvent)
    }
  }

  /** A parent can consume later in Main. Recheck in Final before continuing the session. */
  fun onFinal(event: PointerEvent) {
    when (route) {
      Route.Platform ->
        if (!claimedPlatform && event.changes.any { it.isConsumed }) {
          routing.intercept()
          platform.cancel()
        }
      Route.Scroll -> Unit
      Route.Pointer -> consumption.final(event)
    }
  }

  /** A recognized drag, pinch, or tap takes the camera from scroll and host transforms. */
  fun onPointerRecognized() {
    scroll.cancel()
    platform.cancel()
    platformActive = false
  }

  fun cancel() {
    gesture.cancel()
    scroll.cancel()
    platform.cancel()
  }

  private fun onPlatform(event: PointerEvent, ready: Boolean) {
    if (!platformActive) {
      gesture.cancel()
      platformActive = true
    }
    val admitted = consumption.main(event, ready) {}
    val intercepted = !admitted || event.changes.any { it.isConsumed }
    if (intercepted) routing.intercept()
    val change =
      event.changes.firstOrNull { it.scaleFactor != 1f || it.panOffset != Offset.Zero }
        ?: event.changes.firstOrNull()
    if (change != null) {
      claimedPlatform =
        platform.onInput(
          event.type,
          event.gestureSample(null, density, change.position),
          change.scaleFactor.toDouble(),
          // Platform pans report a scroll delta (positive = scroll down/right, like a wheel);
          // the camera pans in drag convention (content follows the fingers).
          (-change.panOffset).toLogicalDpOffset(density),
          intercepted || routing.blocked,
        )
      if (claimedPlatform) event.changes.forEach(PointerInputChange::consume)
    }
    if (!platform.isActive && !routing.hasContacts && event.type in PLATFORM_ENDS)
      platformActive = false
  }

  private fun onScrollRecognized() {
    platform.cancel()
    platformActive = false
    consumption.suppress()
  }

  private companion object {
    val PLATFORM_ENDS =
      setOf(PointerEventType.ScaleEnd, PointerEventType.PanEnd, PointerEventType.Release)
  }
}
