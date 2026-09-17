package org.maplibre.compose.location

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue

@Composable
internal fun animatePuckAccuracy(
  accuracy: Float?,
  animationSpec: FiniteAnimationSpec<Float>?,
): Float? {
  if (accuracy == null || !accuracy.isFinite() || animationSpec == null) return accuracy
  val animated by animateFloatAsState(accuracy, animationSpec, label = "Puck accuracy")
  return animated.coerceAtLeast(0f)
}
