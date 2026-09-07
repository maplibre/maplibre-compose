package org.maplibre.compose.interaction.internal

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import kotlin.math.ln
import kotlin.math.pow
import org.maplibre.compose.camera.internal.CameraInputTarget
import org.maplibre.compose.camera.internal.CameraInputToken
import org.maplibre.compose.camera.internal.inputPanBy
import org.maplibre.compose.camera.internal.inputRotateAndPitchBy
import org.maplibre.compose.camera.internal.inputScaleBy
import org.maplibre.compose.interaction.CameraInputOrigin
import org.maplibre.compose.interaction.DragEvent
import org.maplibre.compose.interaction.GestureCancellationReason
import org.maplibre.compose.interaction.MapInteractions
import org.maplibre.compose.interaction.PinchEvent
import org.maplibre.compose.interaction.RotateEvent
import org.maplibre.compose.interaction.ScreenVelocity
import org.maplibre.compose.interaction.ShoveEvent

/** Adapts shared screen-space recognition to map events, response gains, and camera ownership. */
internal class PointerPairGesture(
  private val target: CameraInputTarget,
  options: MapInteractions,
  private val currentOptions: () -> MapInteractions,
  private val ids: GestureIds,
  private val density: Density,
  event: PointerEvent,
  first: PointerInputChange,
  second: PointerInputChange,
  private val begin: () -> CameraInputToken?,
  private val onRecognized: (TransformComponent) -> Unit,
  private val retainAuthority: () -> Boolean,
  maximumFlingVelocity: Float = Float.MAX_VALUE,
) {
  private class Component {
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
  private val settings = options.bindings.transform
  private val pan =
    if (options.camera.pan.enabled && settings.pan.matches(metadata)) Component() else null
  private val pinch =
    if (options.camera.zoom.enabled && settings.zoom.matches(metadata)) Component() else null
  private val rotate =
    if (options.camera.rotate.enabled && settings.rotate.matches(metadata)) Component() else null
  private val shove =
    if (options.camera.tilt.enabled && settings.tilt.matches(metadata)) Component() else null
  private val components =
    mapOf(
      TransformComponent.Pan to pan,
      TransformComponent.Scale to pinch,
      TransformComponent.Rotation to rotate,
      TransformComponent.VerticalDrag to shove,
    )
  val hasDemand: Boolean
    get() = components.values.any { it != null }

  private var token: CameraInputToken? = null
  private var cancellationReason = GestureCancellationReason.BindingChanged
  private var endTime = metadata.uptimeMillis
  private val recognition =
    PointerTransform(
      first,
      second,
      TransformRecognitionPolicy(
        density,
        settings.pan.takeIf { pan != null },
        settings.zoom.takeIf { pinch != null },
        settings.rotate.takeIf { rotate != null },
        settings.tilt.takeIf { shove != null },
      ),
      ::start,
      ::delta,
      ::endComponent,
      ::cancelComponent,
      maximumFlingVelocity,
    )
  val firstId
    get() = recognition.firstId

  val secondId
    get() = recognition.secondId

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

    onRecognized(kind)
    val component = checkNotNull(components[kind])
    token?.origin = CameraInputOrigin.Transform
    token?.rearm(kind.cameraComponent)

    val sample = metadata.copy(gestureId = ids.next())
    component.sample = sample
    val position = DpOffset((origin.x / density.density).dp, (origin.y / density.density).dp)
    val handlers = currentOptions().bindings.transform
    when (kind) {
      TransformComponent.Pan -> handlers.pan.handlers.observe(DragEvent.Start(sample, position))
      TransformComponent.Scale -> handlers.zoom.handlers.observe(PinchEvent.Start(sample, position))
      TransformComponent.Rotation ->
        handlers.rotate.handlers.observe(RotateEvent.Start(sample, position))
      TransformComponent.VerticalDrag ->
        handlers.tilt.handlers.observe(ShoveEvent.Start(sample, position))
    }

    return retainAuthority()
  }

  private fun delta(kind: TransformComponent, delta: TransformDecision): Boolean {
    val component = checkNotNull(components[kind])
    val sample = checkNotNull(component.sample)

    // Deliver measured motion before applying response gains. Every observer may revoke camera
    // ownership, so each component checks authority before issuing its command.
    when (kind) {
      TransformComponent.Pan -> {
        val offset =
          DpOffset((delta.pan.x / density.density).dp, (delta.pan.y / density.density).dp)
        currentOptions().bindings.transform.pan.handlers.observe(DragEvent.Delta(sample, offset))
        if (!retainAuthority()) return false

        target.inputPanBy(
          offset.x.value.toDouble(),
          offset.y.value.toDouble(),
          gestureToken = token,
        )
      }
      TransformComponent.Scale -> {
        currentOptions()
          .bindings
          .transform
          .zoom
          .handlers
          .observe(PinchEvent.Delta(sample, delta.scale))
        if (!retainAuthority()) return false

        target.inputScaleBy(
          GestureMath.pinchScale(delta.scale).pow(settings.zoom.zoomScale),
          settings.zoom.anchor.location(metadata),
          gestureToken = token,
        )
      }
      TransformComponent.Rotation -> {
        currentOptions()
          .bindings
          .transform
          .rotate
          .handlers
          .observe(RotateEvent.Delta(sample, delta.rotation))
        if (!retainAuthority()) return false

        target.inputRotateAndPitchBy(
          -delta.rotation * settings.rotate.rotationScale,
          0.0,
          anchor = settings.rotate.anchor.location(metadata),
          gestureToken = token,
        )
      }
      TransformComponent.VerticalDrag -> {
        currentOptions()
          .bindings
          .transform
          .tilt
          .handlers
          .observe(ShoveEvent.Delta(sample, (delta.verticalDrag / density.density).dp))
        if (!retainAuthority()) return false

        target.inputRotateAndPitchBy(
          0.0,
          delta.verticalDrag / density.density * settings.tilt.pitchDegreesPerDp,
          gestureToken = token,
        )
      }
    }

    return retainAuthority()
  }

  private fun cancelComponent(kind: TransformComponent) {
    val component = checkNotNull(components[kind])
    val sample = component.sample ?: return
    component.sample = null
    val handlers = currentOptions().bindings.transform
    when (kind) {
      TransformComponent.Pan ->
        handlers.pan.handlers.observe(DragEvent.Cancel(sample, cancellationReason))
      TransformComponent.Scale ->
        handlers.zoom.handlers.observe(PinchEvent.Cancel(sample, cancellationReason))
      TransformComponent.Rotation ->
        handlers.rotate.handlers.observe(RotateEvent.Cancel(sample, cancellationReason))
      TransformComponent.VerticalDrag ->
        handlers.tilt.handlers.observe(ShoveEvent.Cancel(sample, cancellationReason))
    }
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

    val handlers = currentOptions().bindings.transform
    when (kind) {
      TransformComponent.Pan -> handlers.pan.handlers.observe(DragEvent.End(sample, linear))
      TransformComponent.Scale ->
        handlers.zoom.handlers.observe(
          PinchEvent.End(
            sample,
            velocity.logarithmicScale * ln(GestureMath.pinchScale(kotlin.math.E)) / ln(2.0),
          )
        )
      TransformComponent.Rotation ->
        handlers.rotate.handlers.observe(RotateEvent.End(sample, velocity.rotation))
      TransformComponent.VerticalDrag ->
        handlers.tilt.handlers.observe(ShoveEvent.End(sample, linear))
    }

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
    val centroid = velocity.centroid

    val panFling =
      pan
        ?.takeIf { it.active }
        ?.let { settings.pan.momentum.takeIf { it.enabled } }
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
        ?.let { settings.zoom.momentum.takeIf { it.enabled } }
        ?.let {
          GestureMath.scaleVelocity(
            velocity.logarithmicScale * ln(GestureMath.pinchScale(kotlin.math.E)) / ln(2.0) *
              settings.zoom.zoomScale,
            it,
          )
        }

    val rotation =
      rotate
        ?.takeIf { it.active }
        ?.let { settings.rotate.momentum.takeIf { it.enabled } }
        ?.let {
          GestureMath.rotationVelocity(
            -velocity.rotation * settings.rotate.rotationScale,
            it,
          )
        }

    val tilt =
      shove
        ?.takeIf { it.active }
        ?.let { settings.tilt.momentum.takeIf { it.enabled } }
        ?.let {
          GestureMath.tiltVelocity(
            centroid.y / density.density * settings.tilt.pitchDegreesPerDp,
            it,
          )
        }

    if (panFling == null && scale == null && rotation == null && tilt == null) return null
    return PairContinuation(
      panFling,
      scale,
      rotation,
      tilt,
      settings.zoom.anchor.location(metadata),
      settings.rotate.anchor.location(metadata),
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
) {
  fun without(component: TransformComponent): PairContinuation =
    when (component) {
      TransformComponent.Pan -> copy(pan = null)
      TransformComponent.Scale -> copy(scale = null)
      TransformComponent.Rotation -> copy(rotation = null)
      TransformComponent.VerticalDrag -> copy(tilt = null)
    }

  fun withPrevious(previous: PairContinuation?): PairContinuation =
    copy(
      pan = pan ?: previous?.pan,
      scale = scale ?: previous?.scale,
      rotation = rotation ?: previous?.rotation,
      tilt = tilt ?: previous?.tilt,
      scaleAnchor = if (scale != null) scaleAnchor else previous?.scaleAnchor,
      rotationAnchor = if (rotation != null) rotationAnchor else previous?.rotationAnchor,
    )
}

internal val TransformComponent.cameraComponent: CameraComponent
  get() =
    when (this) {
      TransformComponent.Pan -> CameraComponent.Pan
      TransformComponent.Scale -> CameraComponent.Zoom
      TransformComponent.Rotation -> CameraComponent.Rotate
      TransformComponent.VerticalDrag -> CameraComponent.Tilt
    }
