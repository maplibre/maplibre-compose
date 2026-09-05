package org.maplibre.compose.map

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import kotlin.math.ln
import kotlin.math.pow
import org.maplibre.compose.input.PointerTransform
import org.maplibre.compose.input.TransformComponent
import org.maplibre.compose.input.TransformDecision
import org.maplibre.compose.input.TransformVelocity

/** Adapts shared screen-space recognition to map events, response gains, and camera ownership. */
internal class PointerPairGesture(
  private val target: GestureTarget,
  private val options: MapGestures,
  private val currentOptions: () -> MapGestures,
  private val ids: GestureIds,
  private val density: Density,
  event: PointerEvent,
  first: PointerInputChange,
  second: PointerInputChange,
  private val begin: () -> GestureToken?,
  private val onRecognized: () -> Unit,
  private val retainAuthority: () -> Boolean,
) {
  private class Component(val binding: GestureBinding) {
    var sample: GesturePointerSample? = null
    val active: Boolean
      get() = sample != null
  }

  private var metadata =
    event.gestureSample(
      0,
      target,
      density,
      (first.position + second.position) / 2f,
      setOf(first.type, second.type),
    )
  private val pan = component("dragPan")
  private val pinch = component("pinchZoom")
  private val rotate = component("twoFingerRotate")
  private val shove = component("twoFingerTilt")
  private val components =
    mapOf(
      TransformComponent.Pan to pan,
      TransformComponent.Scale to pinch,
      TransformComponent.Rotation to rotate,
      TransformComponent.VerticalDrag to shove,
    )
  val hasDemand: Boolean
    get() = components.values.any { it != null }

  private var token: GestureToken? = null
  private var cancellationReason = GestureCancellationReason.BindingChanged
  private var endTime = metadata.uptimeMillis
  private val recognition =
    PointerTransform(
      first,
      second,
      MapTransformPolicy(
        density,
        pan?.binding?.settings,
        pinch?.binding?.settings,
        rotate?.binding?.settings,
        shove?.binding?.settings,
      ),
      ::start,
      ::delta,
      ::endComponent,
      ::cancelComponent,
    )
  val firstId
    get() = recognition.firstId

  val secondId
    get() = recognition.secondId

  private fun component(id: String): Component? =
    options.binding(id).takeIf { it.matches(metadata, contact = true) }?.let(::Component)

  fun matches(first: PointerInputChange, second: PointerInputChange): Boolean =
    first.id == firstId && second.id == secondId

  private fun sample(event: PointerEvent, first: PointerInputChange, second: PointerInputChange) {
    metadata =
      event.gestureSample(
        0,
        target,
        density,
        (first.position + second.position) / 2f,
        setOf(first.type, second.type),
      )
    components.values.filterNotNull().forEach { component ->
      component.sample?.let { component.sample = metadata.copy(gestureId = it.gestureId) }
    }
  }

  fun rebase(event: PointerEvent, first: PointerInputChange, second: PointerInputChange) {
    sample(event, first, second)
    recognition.rebase(first, second)
  }

  fun move(event: PointerEvent, first: PointerInputChange, second: PointerInputChange) {
    sample(event, first, second)
    if (recognition.move(first, second))
      event.changes.filter { it.id == firstId || it.id == secondId }.forEach { it.consume() }
  }

  private fun start(kind: TransformComponent, origin: Offset): Boolean {
    token = begin()
    if (token?.acceptsCommands != true) {
      retainAuthority()
      return false
    }
    onRecognized()
    val component = checkNotNull(components[kind])
    val sample = metadata.copy(gestureId = ids.next())
    component.sample = sample
    val position = DpOffset((origin.x / density.density).dp, (origin.y / density.density).dp)
    observe(
      component,
      when (kind) {
        TransformComponent.Pan -> DragEvent.Start(sample, position)
        TransformComponent.Scale -> PinchEvent.Start(sample, position)
        TransformComponent.Rotation -> RotateEvent.Start(sample, position)
        TransformComponent.VerticalDrag -> ShoveEvent.Start(sample, position)
      },
    )
    return retainAuthority()
  }

  private fun delta(kind: TransformComponent, delta: TransformDecision): Boolean {
    val component = checkNotNull(components[kind])
    val sample = checkNotNull(component.sample)
    val settings = component.binding.settings
    when (kind) {
      TransformComponent.Pan -> {
        val offset =
          DpOffset((delta.pan.x / density.density).dp, (delta.pan.y / density.density).dp)
        observe(component, DragEvent.Delta(sample, offset))
        if (!retainAuthority()) return false
        target.moveBy(offset.x.value.toDouble(), offset.y.value.toDouble(), gestureToken = token)
      }
      TransformComponent.Scale -> {
        observe(component, PinchEvent.Delta(sample, delta.scale))
        if (!retainAuthority()) return false
        target.scaleBy(
          GestureMath.pinchScale(delta.scale).pow(settings.zoomScale),
          component.binding.anchor(metadata),
          gestureToken = token,
        )
      }
      TransformComponent.Rotation -> {
        observe(component, RotateEvent.Delta(sample, delta.rotation))
        if (!retainAuthority()) return false
        target.rotateAndPitchBy(
          -delta.rotation * settings.rotationScale,
          0.0,
          anchor = component.binding.anchor(metadata),
          gestureToken = token,
        )
      }
      TransformComponent.VerticalDrag -> {
        observe(component, ShoveEvent.Delta(sample, (delta.verticalDrag / density.density).dp))
        if (!retainAuthority()) return false
        target.rotateAndPitchBy(
          0.0,
          delta.verticalDrag / density.density * settings.pitchDegreesPerDp,
          gestureToken = token,
        )
      }
    }
    return true
  }

  private fun observe(component: Component, event: PointerGestureEvent) {
    val handlers = currentOptions().binding(component.binding.id).handlers
    when (event) {
      is DragEvent -> handlers.observe(event)
      is PinchEvent -> handlers.observe(event)
      is RotateEvent -> handlers.observe(event)
      is ShoveEvent -> handlers.observe(event)
      else -> error("Unexpected pair event")
    }
  }

  private fun cancelComponent(kind: TransformComponent) {
    val component = checkNotNull(components[kind])
    val sample = component.sample ?: return
    component.sample = null
    observe(
      component,
      when (kind) {
        TransformComponent.Pan -> DragEvent.Cancel(sample, cancellationReason)
        TransformComponent.Scale -> PinchEvent.Cancel(sample, cancellationReason)
        TransformComponent.Rotation -> RotateEvent.Cancel(sample, cancellationReason)
        TransformComponent.VerticalDrag -> ShoveEvent.Cancel(sample, cancellationReason)
      },
    )
  }

  fun cancel(reason: GestureCancellationReason) {
    cancellationReason = reason
    recognition.cancel()
  }

  private fun endComponent(kind: TransformComponent, velocity: TransformVelocity): Boolean {
    val component = checkNotNull(components[kind])
    val last = component.sample ?: return true
    component.sample = null
    val sample =
      last.copy(
        uptimeMillis = endTime,
        position = target.positionFromScreenLocation(last.screenOffset),
      )
    val linear =
      ScreenVelocity(
        (velocity.centroid.x / density.density).toDouble(),
        (velocity.centroid.y / density.density).toDouble(),
      )
    observe(
      component,
      when (kind) {
        TransformComponent.Pan -> DragEvent.End(sample, linear)
        TransformComponent.Scale ->
          PinchEvent.End(
            sample,
            velocity.logarithmicScale * ln(GestureMath.pinchScale(kotlin.math.E)) / ln(2.0),
          )
        TransformComponent.Rotation -> RotateEvent.End(sample, velocity.rotation)
        TransformComponent.VerticalDrag -> ShoveEvent.End(sample, linear)
      },
    )
    if (token?.acceptsCommands == false) {
      retainAuthority()
      return false
    }
    return true
  }

  fun end(uptimeMillis: Long = metadata.uptimeMillis): PairContinuation? {
    val continuation = continuation()
    endTime = uptimeMillis
    return continuation.takeIf { recognition.end() }
  }

  private fun continuation(): PairContinuation? {
    val velocity = recognition.velocity()
    val finger = velocity.pointer
    val centroid = velocity.centroid
    val panFling =
      pan
        ?.takeIf { it.active }
        ?.binding
        ?.settings
        ?.fling
        ?.let {
          GestureMath.fling(
            (centroid.x / density.density).toDouble(),
            (centroid.y / density.density).toDouble(),
            it,
          )
        }
    val scale =
      pinch
        ?.takeIf { it.active }
        ?.binding
        ?.settings
        ?.velocityContinuation
        ?.let {
          GestureMath.scaleVelocity(
              finger.x.toDouble(),
              finger.y.toDouble(),
              velocity.lastSpanDelta,
              density.density.toDouble(),
              velocity.scalingOut,
              it,
            )
            ?.let { response ->
              response.copy(zoomDelta = response.zoomDelta * pinch.binding.settings.zoomScale)
            }
        }
    val rotation =
      rotate
        ?.takeIf { it.active }
        ?.binding
        ?.settings
        ?.velocityContinuation
        ?.let {
          GestureMath.rotationVelocity(
              finger.x.toDouble(),
              finger.y.toDouble(),
              recognition.current.centroid.x.toDouble(),
              recognition.current.centroid.y.toDouble(),
              -velocity.lastRotation,
              density.density.toDouble(),
              pinch?.active == true,
              it,
            )
            ?.let { response ->
              response.copy(
                initialDegreesPerFrame =
                  response.initialDegreesPerFrame * rotate.binding.settings.rotationScale
              )
            }
        }
    val tilt =
      shove
        ?.takeIf { it.active }
        ?.binding
        ?.settings
        ?.tiltContinuation
        ?.let {
          GestureMath.tiltVelocity(
            centroid.y / density.density * shove.binding.settings.pitchDegreesPerDp,
            it,
          )
        }
    if (panFling == null && scale == null && rotation == null && tilt == null) return null
    return PairContinuation(
      panFling,
      scale,
      rotation,
      tilt,
      pinch?.binding?.anchor(metadata),
      rotate?.binding?.anchor(metadata),
    )
  }
}

internal data class PairContinuation(
  val pan: GestureMath.Fling?,
  val scale: GestureMath.ScaleVelocity?,
  val rotation: GestureMath.RotationVelocity?,
  val tilt: GestureMath.TiltVelocity?,
  val scaleAnchor: DpOffset?,
  val rotationAnchor: DpOffset?,
)
