package org.maplibre.compose.interaction.internal

import androidx.compose.ui.geometry.Offset
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
import org.maplibre.compose.interaction.GestureAnchor

/** Applies camera response gains and momentum to a recognized touch pair. */
internal class PointerPairGesture(
  private val target: CameraInputTarget,
  options: InputConfiguration,
  private val density: Density,
  initialInput: GesturePointerSample,
  first: PointerInputChange,
  second: PointerInputChange,
  private val begin: () -> CameraInputToken?,
  private val onRecognized: (CameraComponent) -> Unit,
  private val retainAuthority: () -> Boolean,
  maximumFlingVelocity: Float = Float.MAX_VALUE,
  touchSlopPx: Float,
) {
  private val settings = options.bindings.transform
  private val pan =
    settings.pan.takeIf { options.camera.settings.pan.enabled && it.matches(initialInput) }
  private val pinch =
    settings.zoom.takeIf { options.camera.settings.zoom.enabled && it.matches(initialInput) }
  private val rotate =
    settings.rotate.takeIf { options.camera.settings.rotate.enabled && it.matches(initialInput) }
  private val shove =
    settings.pitch.takeIf { options.camera.settings.pitch.enabled && it.matches(initialInput) }
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
      touchSlopPx,
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

  fun move(first: PointerInputChange, second: PointerInputChange) {
    centroid = ((first.position + second.position) / 2f).toLogicalDpOffset(density)
    if (recognition.move(first, second)) {
      first.consume()
      second.consume()
    }
  }

  private fun start(kind: CameraComponent): Boolean {
    token = begin()
    if (token?.acceptsCommands != true) return false
    onRecognized(kind)
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
      CameraComponent.Pitch -> {
        target.inputRotateAndPitchBy(
          0.0,
          delta.verticalDrag / density.density * settings.pitch.pitchDegreesPerDp,
          gestureToken = token,
        )
      }
    }

    return retainAuthority()
  }

  fun cancel() = recognition.cancel()

  /** Ends recognition and returns how fast each recognized component moved at release. */
  fun end(): ReleaseVelocity? {
    val release = if (token?.acceptsCommands == true) release() else null
    recognition.cancel()
    return release
  }

  private fun release(): ReleaseVelocity? {
    val velocity = recognition.velocity()
    val panVelocity = velocity.centroid
    fun recognized(component: CameraComponent) = component in recognition.active

    fun Double.moving() = takeIf { it.isFinite() && it != 0.0 }

    val pan =
      Offset(panVelocity.x / density.density, panVelocity.y / density.density).takeIf {
        pan != null && recognized(CameraComponent.Pan) && it != Offset.Zero
      }
    val zoom =
      (velocity.logarithmicScale * ln(GestureMath.pinchScale(kotlin.math.E)) / ln(2.0) *
          settings.zoom.zoomScale)
        .takeIf { pinch != null && recognized(CameraComponent.Zoom) }
        ?.moving()
    val bearing =
      (-velocity.rotation * settings.rotate.rotationScale)
        .takeIf { rotate != null && recognized(CameraComponent.Rotate) }
        ?.moving()
    val pitch =
      (panVelocity.y / density.density * settings.pitch.pitchDegreesPerDp)
        .takeIf { shove != null && recognized(CameraComponent.Pitch) }
        ?.moving()

    if (pan == null && zoom == null && bearing == null && pitch == null) return null
    return ReleaseVelocity(
      pan = pan,
      zoom = zoom,
      bearing = bearing,
      pitch = pitch,
      zoomAnchor = centroid.takeIf { settings.zoom.anchor == GestureAnchor.Input },
      bearingAnchor = centroid.takeIf { settings.rotate.anchor == GestureAnchor.Input },
    )
  }
}
