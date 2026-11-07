package org.maplibre.compose.interaction.internal

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.DpOffset
import kotlin.math.ln
import kotlin.math.pow
import kotlinx.coroutines.CoroutineScope
import org.maplibre.compose.camera.internal.CameraInputTarget
import org.maplibre.compose.camera.internal.inputPanBy
import org.maplibre.compose.camera.internal.inputScaleBy
import org.maplibre.compose.interaction.internal.PlatformTransformRouting.Kind

/**
 * Host pans keep their supplied inertia; host scales use the configured zoom release momentum. The
 * momentum continues the gesture once every component has ended, if the last one ended with the
 * scale.
 */
internal class PlatformTransformSession(
  private val target: CameraInputTarget,
  private val options: InputConfiguration,
  scope: CoroutineScope,
  private val routing: PlatformTransformRouting,
  private val onAccepted: () -> Unit,
) {
  private val components = mutableSetOf<Kind>()
  private val burst = InputBurst(scope, target) { cancel() }
  private val scaleVelocity = GestureVelocityTracker()
  private var zoomLevels = 0.0
  private var scaleChanges = 0
  private var lastScaleTime = 0L
  private var scaleAnchor: DpOffset? = null
  private var release: ReleaseVelocity? = null
  private var releaseTime = 0L
  private var continuationSession: GestureInputSession? = null
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

    if (burst.session?.token?.acceptsCommands == false) cancel()

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
      if (kind == Kind.Scale) finishScale(sample)
      finishIfIdle(sample)
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
          Kind.Scale -> options.camera.settings.zoom.enabled && settings.zoom.matches(sample)
          Kind.Pan -> options.camera.settings.pan.enabled && settings.pan.matches(sample)
        }
    if (!eligible) {
      routing.suppressed += kind
      components.remove(kind)
      if (kind == Kind.Scale) resetScale()
      finishIfIdle(sample)
      return false
    }

    if (kind !in components) {
      startComponent(kind, sample)
      if (!retainAuthority()) return true
    }
    if (!delta) return true

    target.observeInput()
    val token = checkNotNull(burst.session).token
    when (kind) {
      Kind.Scale -> {
        val scale = scaleFactor.pow(settings.zoom.zoomScale)
        if (scale.isFinite() && scale > 0.0) {
          if (scale != 1.0) scaleChanges++
          zoomLevels += ln(scale) / ln(2.0)
          scaleAnchor = settings.zoom.anchor.location(sample)
          lastScaleTime = sample.uptimeMillis
          scaleVelocity.addPosition(sample.uptimeMillis, Offset(zoomLevels.toFloat(), 0f))
          target.inputScaleBy(
            scale,
            scaleAnchor,
            gestureToken = token,
          )
        }
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

  private fun startComponent(kind: Kind, sample: GesturePointerSample) {
    val session =
      burst.session
        ?: run {
          continuationSession?.cancel()
          continuationSession = null
          onAccepted()
          target.observeInput()
          burst.start()
        }

    session.token.rearm(if (kind == Kind.Scale) CameraComponent.Zoom else CameraComponent.Pan)
    if (kind == Kind.Scale) {
      resetScale()
      scaleVelocity.addPosition(sample.uptimeMillis, Offset.Zero)
    }
    components += kind
  }

  private fun resetScale() {
    release = null
    scaleVelocity.resetTracking()
    zoomLevels = 0.0
    scaleChanges = 0
    scaleAnchor = null
  }

  private fun finishScale(sample: GesturePointerSample) {
    // A discrete scale step (including smart magnify) supplies no release velocity.
    if (scaleChanges < 2) return
    // Compose's pointer velocity tracker treats a release more than 40 ms after movement as
    // stopped. Do not insert an artificial stationary sample into the scale velocity fit.
    if (
      sample.uptimeMillis < lastScaleTime ||
        sample.uptimeMillis - lastScaleTime > ReleaseWindowMillis
    )
      return
    releaseTime = sample.uptimeMillis
    release =
      ReleaseVelocity(
        zoom = scaleVelocity.calculateVelocity(pointerInput = false).x.toDouble(),
        zoomAnchor = scaleAnchor,
      )
  }

  private fun retainAuthority(): Boolean {
    if (burst.session?.token?.acceptsCommands == true) return true
    cancel()
    return false
  }

  private fun finishIfIdle(sample: GesturePointerSample) {
    if (components.isNotEmpty()) return
    // A scale that stopped well before the gesture ended has no speed left to continue.
    val momentum = release?.takeIf { sample.uptimeMillis - releaseTime <= ReleaseWindowMillis }
    release = null
    continuationSession = burst.end(momentum)?.takeIf { momentum != null }
  }

  fun cancel() {
    routing.suppressed += components
    components.clear()
    burst.cancel()
    continuationSession?.cancel()
    continuationSession = null
    resetScale()
  }
}

/** Compose's pointer velocity tracker treats a release this long after movement as stopped. */
private const val ReleaseWindowMillis = 40L
