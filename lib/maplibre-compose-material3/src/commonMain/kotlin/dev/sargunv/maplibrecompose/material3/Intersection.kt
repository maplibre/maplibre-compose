package dev.sargunv.maplibrecompose.material3

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Given an imaginary line drawn from the center of [screen] to [target], returns the point and
 * angle at which the line intersects with an ellipsis filling the [screen].
 */
internal fun findEllipsisIntersection(screen: Rect, target: Offset): Intersection? {
  val delta = target - screen.center
  val theta = atan2(delta.y, delta.x) + PI / 2
  val radius = ellipsisRadius(screen.height / 2.0, screen.width / 2.0, theta)
  val ellipsisDelta = Offset((sin(theta) * radius).toFloat(), (-cos(theta) * radius).toFloat())
  if (delta.getDistanceSquared() < ellipsisDelta.getDistanceSquared()) return null

  return Intersection(ellipsisDelta + screen.center, theta)
}

private fun ellipsisRadius(a: Double, b: Double, angle: Double): Double {
  val x = sin(angle)
  val y = cos(angle)
  return a * b / sqrt(a * a * x * x + b * b * y * y)
}

internal data class Intersection(val position: Offset, val angle: Double)
