package org.maplibre.compose.map

import kotlin.time.DurationUnit
import org.maplibre.compose.interaction.internal.AnchoredMomentumPart
import org.maplibre.compose.interaction.internal.GestureMomentum
import org.maplibre.compose.interaction.internal.GlideDecay
import org.maplibre.compose.interaction.internal.GlideVelocity
import org.maplibre.compose.interaction.internal.Snapping
import org.maplibre.compose.util.toScreenPoint
import org.maplibre.nativeffi.AnchoredMomentum
import org.maplibre.nativeffi.CameraVelocity
import org.maplibre.nativeffi.GestureMomentum as FfiGestureMomentum
import org.maplibre.nativeffi.MomentumDecay
import org.maplibre.nativeffi.MomentumDecayModel
import org.maplibre.nativeffi.MomentumSnap
import org.maplibre.nativeffi.PanMomentum
import org.maplibre.nativeffi.ScreenPoint
import org.maplibre.nativeffi.UnitBezier

/**
 * Sets every decay and snap setting, so the core uses the library's values rather than its own
 * defaults. Velocities and distances are in logical pixels, which are dp.
 */
internal fun GestureMomentum.toFfi(): FfiGestureMomentum =
  FfiGestureMomentum(
    pan =
      pan?.let {
        PanMomentum(velocity = ScreenPoint(it.velocityX, it.velocityY), decay = it.decay.toFfi())
      } ?: PanMomentum(),
    zoom = zoom?.toFfi() ?: AnchoredMomentum(),
    bearing = bearing?.toFfi() ?: AnchoredMomentum(),
    pitch = pitch?.toFfi() ?: AnchoredMomentum(),
  )

private fun AnchoredMomentumPart.toFfi(): AnchoredMomentum =
  AnchoredMomentum(
    velocity = velocity,
    anchor = anchor?.toScreenPoint(),
    decay = decay.toFfi(),
    snap = snap?.toFfi(),
  )

private fun GlideDecay.toFfi(): MomentumDecay =
  when (this) {
    is GlideDecay.Friction ->
      MomentumDecay(
        model = MomentumDecayModel.FRICTION,
        halfLifeMs = settings.halfLife.toDouble(DurationUnit.MILLISECONDS),
        stopSpeed = settings.stopSpeed,
        minSpeed = settings.minSpeed,
        maxSpeed = settings.maxSpeed.finite(),
        maxDistance = settings.maxDistance.finite(),
      )
    is GlideDecay.Ease ->
      MomentumDecay(
        model = MomentumDecayModel.EASE,
        distance = glide.distance,
        durationMs = glide.duration.toDouble(DurationUnit.MILLISECONDS),
        easing = glide.easing.let { UnitBezier(it.x1, it.y1, it.x2, it.y2) },
        minSpeed = minSpeed,
        maxDistance = maxDistance.finite(),
      )
  }

private fun Snapping.toFfi(): MomentumSnap =
  MomentumSnap(
    spacing = spacing,
    offset = offset,
    tolerance = tolerance,
    durationMs = duration.toDouble(DurationUnit.MILLISECONDS),
  )

/** The core takes only finite limits; the largest one is no limit. */
private fun Double.finite(): Double =
  if (this == Double.POSITIVE_INFINITY) Double.MAX_VALUE else this

internal fun CameraVelocity.toGlideVelocity(): GlideVelocity =
  GlideVelocity(pan.x, pan.y, zoom, bearing, pitch)
