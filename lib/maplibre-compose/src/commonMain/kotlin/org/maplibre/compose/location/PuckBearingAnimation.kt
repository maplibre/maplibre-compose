package org.maplibre.compose.location

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import kotlin.math.abs
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import org.maplibre.spatialk.units.Bearing
import org.maplibre.spatialk.units.extensions.inDegrees

@Composable
internal fun animatePuckBearing(
  bearing: Bearing?,
  animationSpec: FiniteAnimationSpec<Float>?,
): Float? {
  // Leaving this group discards the animation on absence or caller-controlled rendering.
  if (bearing == null) return null
  val degrees = (bearing - Bearing.North).inDegrees.toFloat()
  if (animationSpec == null) return degrees

  val animation = remember { Animatable(degrees) }
  val target by rememberUpdatedState(degrees to animationSpec)
  LaunchedEffect(animation) {
    snapshotFlow { target }
      .collect { (degrees, spec) ->
        // Let animateTo interrupt its predecessor so it captures velocity before cancellation.
        // Starting immediately also allows progress when measurements arrive every frame.
        launch(start = CoroutineStart.UNDISPATCHED) {
          val current = animation.value
          val delta = ((degrees - current) % 360f + 540f) % 360f - 180f
          if (abs(current) > 3600f) {
            // Bound Float magnitudes even during continuous rotation. Translation preserves the
            // orientation and angular velocity, unlike resetting the animation to the measurement.
            val velocity = animation.velocity
            val rebased = current % 360f
            animation.snapTo(rebased)
            animation.animateTo(rebased + delta, spec, initialVelocity = velocity)
          } else if (delta != 0f || animation.isRunning) {
            animation.animateTo(current + delta, spec)
          }
        }
      }
  }
  return animation.value
}
