package org.maplibre.compose.interaction.internal

import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.DpOffset
import kotlin.math.pow
import kotlinx.coroutines.CoroutineScope
import org.maplibre.compose.camera.internal.CameraInputTarget
import org.maplibre.compose.camera.internal.inputPanBy
import org.maplibre.compose.camera.internal.inputScaleBy
import org.maplibre.compose.interaction.CameraInputOrigin
import org.maplibre.compose.interaction.MapInteractions
import org.maplibre.compose.interaction.internal.PlatformTransformRouting.Kind

/** Host-recognized components share one camera session and append no library momentum. */
internal class PlatformTransformSession(
  private val target: CameraInputTarget,
  private val options: MapInteractions,
  private val scope: CoroutineScope,
  private val routing: PlatformTransformRouting,
  private val onAccepted: () -> Unit,
) {
  private val components = mutableSetOf<Kind>()
  private var session: GestureInputSession? = null
  val isActive: Boolean
    get() = components.isNotEmpty()

  /** Returns whether this event belongs to an accepted component and must be consumed in Main. */
  fun onInput(
    type: PointerEventType,
    sample: GesturePointerSample,
    scaleFactor: Double = 1.0,
    panDelta: DpOffset = DpOffset.Zero,
    consumed: Boolean = false,
  ): Boolean {
    if (!isPlatformTransform(type)) {
      if (consumed) cancel()
      return false
    }

    val kind =
      if (
        type == PointerEventType.ScaleStart ||
          type == PointerEventType.ScaleChange ||
          type == PointerEventType.ScaleEnd
      )
        Kind.Scale
      else Kind.Pan
    val end = type == PointerEventType.ScaleEnd || type == PointerEventType.PanEnd

    if (session?.token?.acceptsCommands == false) cancel()

    // A cancelled component stays suppressed until the host ends it; later deltas must not
    // reopen a camera session after another handler has taken the input.
    if (consumed) {
      routing.suppressed += kind
      cancel()
      if (end) routing.suppressed.remove(kind)
      return false
    }

    if (end) {
      routing.suppressed.remove(kind)
      if (!components.remove(kind)) return false
      finishIfIdle()
      return true
    }

    if (kind in routing.suppressed) return false
    val delta = type == PointerEventType.ScaleChange || type == PointerEventType.PanMove
    if (
      delta &&
        (if (kind == Kind.Scale) !scaleFactor.isFinite() || scaleFactor <= 0.0
        else !panDelta.x.value.isFinite() || !panDelta.y.value.isFinite())
    )
      return false

    val settings = options.bindings.transform
    val eligible =
      target.isGestureReady &&
        when (kind) {
          Kind.Scale -> options.camera.zoom.enabled && settings.zoom.matches(sample)
          Kind.Pan -> options.camera.pan.enabled && settings.pan.matches(sample)
        }
    if (!eligible) {
      routing.suppressed += kind
      components.remove(kind)
      finishIfIdle()
      return false
    }

    if (kind !in components) {
      startComponent(kind)
      if (!retainAuthority()) return true
    }
    if (!delta) return true

    target.observeInput()
    val token = checkNotNull(session).token
    when (kind) {
      Kind.Scale -> {
        val scale = scaleFactor.pow(settings.zoom.zoomScale)
        if (scale.isFinite() && scale > 0.0)
          target.inputScaleBy(
            scale,
            settings.zoom.anchor.location(sample),
            gestureToken = token,
          )
      }
      Kind.Pan ->
        target.inputPanBy(
          panDelta.x.value.toDouble(),
          panDelta.y.value.toDouble(),
          gestureToken = token,
        )
    }

    retainAuthority()
    return true
  }

  private fun startComponent(kind: Kind) {
    if (session == null) {
      onAccepted()
      target.observeInput()
      lateinit var input: GestureInputSession
      input =
        GestureInputSession(scope, target, origin = CameraInputOrigin.Transform) {
          if (session === input) cancel()
        }
      session = input
    }

    session?.token?.rearm(if (kind == Kind.Scale) CameraComponent.Zoom else CameraComponent.Pan)
    components += kind
  }

  private fun retainAuthority(): Boolean {
    if (session?.token?.acceptsCommands == true) return true
    cancel()
    return false
  }

  private fun finishIfIdle() {
    if (components.isNotEmpty()) return
    val completed = session
    session = null
    completed?.end()
  }

  fun cancel() {
    routing.suppressed += components
    components.clear()
    val cancelled = session
    session = null
    cancelled?.cancel()
  }
}
