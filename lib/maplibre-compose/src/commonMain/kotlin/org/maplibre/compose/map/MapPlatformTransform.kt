package org.maplibre.compose.map

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.DpOffset
import kotlin.math.log2
import kotlin.math.pow
import kotlinx.coroutines.CoroutineScope
import org.maplibre.compose.input.GestureVelocityTracker
import org.maplibre.compose.map.PlatformTransformRouting.Kind

/** Host-recognized components share one camera session and append no library momentum. */
internal class MapPlatformTransform(
  private val target: GestureTarget,
  private val options: MapGestures,
  private val currentOptions: () -> MapGestures,
  private val ids: GestureIds,
  private val scope: CoroutineScope,
  private val routing: PlatformTransformRouting,
  private val onAccepted: () -> Unit,
) {
  private class Component(
    val kind: Kind,
    val binding: GestureBinding,
    var sample: GesturePointerSample,
  ) {
    val velocity = GestureVelocityTracker()
    var displacement = Offset.Zero
  }

  private val components = mutableMapOf<Kind, Component>()
  private val suppressed
    get() = routing.suppressed

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
      if (consumed) cancel(GestureCancellationReason.InputConsumed)
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
    if (session?.token?.acceptsCommands == false)
      cancel(
        if (target.isGestureReady) GestureCancellationReason.CameraTakeover
        else GestureCancellationReason.Detached
      )
    if (consumed) {
      suppressed += kind
      cancel(GestureCancellationReason.InputConsumed)
      if (end) suppressed.remove(kind)
      return false
    }
    if (end) {
      suppressed.remove(kind)
      val previous = components.remove(kind) ?: return false
      previous.sample = previous.sample.copy(uptimeMillis = sample.uptimeMillis)
      try {
        deliverEnd(previous)
      } finally {
        finishIfIdle()
      }
      return true
    }
    if (kind in suppressed) return false
    val delta = type == PointerEventType.ScaleChange || type == PointerEventType.PanMove
    if (
      delta &&
        (if (kind == Kind.Scale) !scaleFactor.isFinite() || scaleFactor <= 0.0
        else !panDelta.x.value.isFinite() || !panDelta.y.value.isFinite())
    )
      return false
    val selected = options.binding(if (kind == Kind.Scale) "pinchZoom" else "dragPan")
    if (
      !selected.enabled ||
        selected.filters.none {
          it.matches(
            sample.pointerTypes,
            sample.buttons,
            sample.modifierKeys,
            contact = true,
            platformTransform = true,
          )
        }
    ) {
      if (components.containsKey(kind)) {
        suppressed += kind
        cancel(GestureCancellationReason.BindingChanged)
      }
      return false
    }
    var current = components[kind]
    if (
      current != null &&
        (current.sample.modifierKeys != sample.modifierKeys ||
          current.sample.buttons != sample.buttons ||
          current.sample.pointerTypes != sample.pointerTypes)
    ) {
      suppressed += kind
      cancel(GestureCancellationReason.BindingChanged)
      return false
    }
    if (current == null) {
      if (session == null) {
        onAccepted()
        target.observeInput()
        lateinit var input: GestureInputSession
        input =
          GestureInputSession(scope, target) {
            if (session === input)
              cancel(
                if (target.isGestureReady) GestureCancellationReason.CameraTakeover
                else GestureCancellationReason.Detached
              )
          }
        session = input
      }
      current = Component(kind, selected, sample.copy(gestureId = ids.next()))
      components[kind] = current
      current.velocity.addPosition(sample.uptimeMillis, Offset.Zero)
      deliverStart(current)
      if (!retainAuthority()) return true
    }
    if (!delta) return true
    if (sample.uptimeMillis < current.sample.uptimeMillis) {
      current.velocity.resetTracking()
      current.sample = sample.copy(gestureId = current.sample.gestureId)
      current.velocity.addPosition(sample.uptimeMillis, current.displacement)
      return true
    }
    current.sample = sample.copy(gestureId = current.sample.gestureId)
    target.observeInput()
    current.displacement +=
      if (kind == Kind.Scale) Offset(log2(scaleFactor).toFloat(), 0f)
      else Offset(panDelta.x.value, panDelta.y.value)
    current.velocity.addPosition(sample.uptimeMillis, current.displacement)
    val handlers = handlers(current)
    when (kind) {
      Kind.Scale -> handlers.observe(PinchEvent.Delta(current.sample, scaleFactor))
      Kind.Pan -> handlers.observe(DragEvent.Delta(current.sample, panDelta))
    }
    if (!retainAuthority()) return true
    val token = checkNotNull(session).token
    when (kind) {
      Kind.Scale -> {
        val scale = scaleFactor.pow(selected.settings.zoomScale)
        if (scale.isFinite() && scale > 0.0)
          target.scaleBy(scale, selected.anchor(current.sample), gestureToken = token)
      }
      Kind.Pan ->
        target.moveBy(
          panDelta.x.value.toDouble(),
          panDelta.y.value.toDouble(),
          gestureToken = token,
        )
    }
    return true
  }

  private fun retainAuthority(): Boolean {
    if (session?.token?.acceptsCommands == true) return true
    cancel(
      if (target.isGestureReady) GestureCancellationReason.CameraTakeover
      else GestureCancellationReason.Detached
    )
    return false
  }

  private fun handlers(component: Component): GestureBindingHandlers =
    currentOptions().bindings.firstOrNull { it.id == component.binding.id }?.handlers
      ?: component.binding.handlers

  private fun deliverStart(component: Component) {
    val sample = component.sample
    when (component.kind) {
      Kind.Scale -> handlers(component).observe(PinchEvent.Start(sample, sample.screenOffset))
      Kind.Pan -> handlers(component).observe(DragEvent.Start(sample, sample.screenOffset))
    }
  }

  private fun deliverEnd(component: Component) {
    val velocity = component.velocity.calculateVelocity(pointerInput = false)
    when (component.kind) {
      Kind.Scale ->
        handlers(component).observe(PinchEvent.End(component.sample, velocity.x.toDouble()))
      Kind.Pan ->
        handlers(component)
          .observe(
            DragEvent.End(
              component.sample,
              ScreenVelocity(velocity.x.toDouble(), velocity.y.toDouble()),
            )
          )
    }
  }

  private fun finishIfIdle() {
    if (components.isNotEmpty()) return
    val completed = session
    session = null
    completed?.end()
  }

  fun cancel(reason: GestureCancellationReason = GestureCancellationReason.InputCancelled) {
    val previous = components.values.toList()
    suppressed += components.keys
    components.clear()
    val cancelled = session
    session = null
    var failure: Throwable? = null
    try {
      for (component in previous) {
        try {
          when (component.kind) {
            Kind.Scale -> handlers(component).observe(PinchEvent.Cancel(component.sample, reason))
            Kind.Pan -> handlers(component).observe(DragEvent.Cancel(component.sample, reason))
          }
        } catch (cause: Throwable) {
          if (failure == null) failure = cause else failure.addSuppressed(cause)
        }
      }
    } finally {
      cancelled?.cancel()
    }
    failure?.let { throw it }
  }
}
