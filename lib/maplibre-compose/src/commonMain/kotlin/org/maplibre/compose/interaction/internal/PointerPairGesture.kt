package org.maplibre.compose.interaction.internal

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
import org.maplibre.compose.interaction.GestureAnchor
import org.maplibre.compose.interaction.MapInteractions

/** Applies camera response gains and momentum to a recognized touch pair. */
internal class PointerPairGesture(
  private val target: CameraInputTarget,
  options: MapInteractions,
  private val density: Density,
  event: PointerEvent,
  first: PointerInputChange,
  second: PointerInputChange,
  private val begin: () -> CameraInputToken?,
  private val onRecognized: (CameraComponent) -> Unit,
  private val retainAuthority: () -> Boolean,
  maximumFlingVelocity: Float = Float.MAX_VALUE,
) {
  private val initialInput =
    event.gestureSample(
      null,
      density,
      (first.position + second.position) / 2f,
      setOf(first.type, second.type),
    )
  private val settings = options.bindings.transform
  private val pan = settings.pan.takeIf { options.camera.pan.enabled && it.matches(initialInput) }
  private val pinch =
    settings.zoom.takeIf { options.camera.zoom.enabled && it.matches(initialInput) }
  private val rotate =
    settings.rotate.takeIf { options.camera.rotate.enabled && it.matches(initialInput) }
  private val shove =
    settings.tilt.takeIf { options.camera.tilt.enabled && it.matches(initialInput) }
  val hasDemand: Boolean
    get() = pan != null || pinch != null || rotate != null || shove != null

  private var token: CameraInputToken? = null
  private val recognition =
    PointerTransform(
      first,
      second,
      TransformRecognitionPolicy(density, pan, pinch, rotate, shove),
      ::start,
      ::delta,
      maximumFlingVelocity,
    )
  val firstId
    get() = recognition.firstId

  val secondId
    get() = recognition.secondId

  fun matches(first: PointerInputChange, second: PointerInputChange): Boolean =
    first.id == firstId && second.id == secondId

  private var centroid = initialInput.screenOffset

  fun rebase(first: PointerInputChange, second: PointerInputChange) {
    centroid = ((first.position + second.position) / 2f).toLogicalDpOffset(density)
    recognition.rebase(first, second)
  }

  fun move(event: PointerEvent, first: PointerInputChange, second: PointerInputChange) {
    centroid = ((first.position + second.position) / 2f).toLogicalDpOffset(density)
    if (recognition.move(first, second))
      event.changes.filter { it.id == firstId || it.id == secondId }.forEach { it.consume() }
  }

  private fun start(kind: CameraComponent): Boolean {
    token = begin()
    if (token?.acceptsCommands != true) return false
    onRecognized(kind)
    token?.origin = CameraInputOrigin.Transform
    token?.rearm(kind)
    return true
  }

  private fun delta(kind: CameraComponent, delta: TransformDecision): Boolean {
    when (kind) {
      CameraComponent.Pan -> {
        val offset =
          DpOffset((delta.pan.x / density.density).dp, (delta.pan.y / density.density).dp)
        target.inputPanBy(
          offset.x.value.toDouble(),
          offset.y.value.toDouble(),
          gestureToken = token,
        )
      }
      CameraComponent.Zoom -> {
        target.inputScaleBy(
          GestureMath.pinchScale(delta.scale).pow(settings.zoom.zoomScale),
          centroid.takeIf { settings.zoom.anchor == GestureAnchor.Input },
          gestureToken = token,
        )
      }
      CameraComponent.Rotate -> {
        target.inputRotateAndPitchBy(
          -delta.rotation * settings.rotate.rotationScale,
          0.0,
          anchor = centroid.takeIf { settings.rotate.anchor == GestureAnchor.Input },
          gestureToken = token,
        )
      }
      CameraComponent.Tilt -> {
        target.inputRotateAndPitchBy(
          0.0,
          delta.verticalDrag / density.density * settings.tilt.pitchDegreesPerDp,
          gestureToken = token,
        )
      }
    }

    return retainAuthority()
  }

  fun cancel() = recognition.cancel()

  fun end(): PairContinuation? {
    val continuation = if (token?.acceptsCommands == true) continuation() else null
    recognition.cancel()
    return continuation
  }

  private fun continuation(): PairContinuation? {
    val velocity = recognition.velocity()
    val panVelocity = velocity.centroid

    val panFling =
      pan
        ?.takeIf { CameraComponent.Pan in recognition.active }
        ?.let { settings.pan.momentum.takeIf { it.enabled } }
        ?.let {
          GestureMath.fling(
            (panVelocity.x / density.density).toDouble(),
            (panVelocity.y / density.density).toDouble(),
            it,
          )
        }

    val scale =
      pinch
        ?.takeIf { CameraComponent.Zoom in recognition.active }
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
        ?.takeIf { CameraComponent.Rotate in recognition.active }
        ?.let { settings.rotate.momentum.takeIf { it.enabled } }
        ?.let {
          GestureMath.rotationVelocity(
            -velocity.rotation * settings.rotate.rotationScale,
            it,
          )
        }

    val tilt =
      shove
        ?.takeIf { CameraComponent.Tilt in recognition.active }
        ?.let { settings.tilt.momentum.takeIf { it.enabled } }
        ?.let {
          GestureMath.tiltVelocity(
            panVelocity.y / density.density * settings.tilt.pitchDegreesPerDp,
            it,
          )
        }

    if (panFling == null && scale == null && rotation == null && tilt == null) return null
    return PairContinuation(
      panFling,
      scale,
      rotation,
      tilt,
      centroid.takeIf { settings.zoom.anchor == GestureAnchor.Input },
      centroid.takeIf { settings.rotate.anchor == GestureAnchor.Input },
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
  fun without(component: CameraComponent): PairContinuation =
    when (component) {
      CameraComponent.Pan -> copy(pan = null)
      CameraComponent.Zoom -> copy(scale = null)
      CameraComponent.Rotate -> copy(rotation = null)
      CameraComponent.Tilt -> copy(tilt = null)
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
