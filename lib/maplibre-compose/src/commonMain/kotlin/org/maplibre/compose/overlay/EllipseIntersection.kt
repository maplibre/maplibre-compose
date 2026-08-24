package org.maplibre.compose.overlay

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntOffset
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Given an imaginary line drawn from the center of [area] to [target], returns the point and angle
 * at which the line crosses an ellipse inscribed in [area], or null while [target] lies inside the
 * ellipse.
 */
internal fun findEllipseIntersection(area: Rect, target: Offset): EllipseIntersection? {
  val delta = target - area.center
  val theta = atan2(delta.y, delta.x) + PI / 2
  val radius = ellipseRadius(area.height / 2.0, area.width / 2.0, theta)
  val ellipseDelta = Offset((sin(theta) * radius).toFloat(), (-cos(theta) * radius).toFloat())
  if (delta.getDistanceSquared() < ellipseDelta.getDistanceSquared()) return null

  return EllipseIntersection(ellipseDelta + area.center, theta)
}

private fun ellipseRadius(a: Double, b: Double, angle: Double): Double {
  val x = sin(angle)
  val y = cos(angle)
  return a * b / sqrt(a * a * x * x + b * b * y * y)
}

/**
 * A point on the ellipse and the angle of the line from the ellipse center through it, in radians
 * clockwise from screen-up.
 */
internal data class EllipseIntersection(val position: Offset, val angleRadians: Double)

/**
 * The top-left position of a child of [width] by [height] whose anchor sits on the intersection.
 * The anchor is the point of an ellipse inscribed in the child that faces the target, so a child
 * that draws up to its own inscribed ellipse touches the placement ellipse exactly.
 */
internal fun placedTowardsTopLeft(
  intersection: EllipseIntersection,
  width: Int,
  height: Int,
): IntOffset {
  val theta = intersection.angleRadians
  val anchorX = (width / 2f) * (1f + sin(theta).toFloat())
  val anchorY = (height / 2f) * (1f - cos(theta).toFloat())
  return IntOffset(
    (intersection.position.x - anchorX).roundToInt(),
    (intersection.position.y - anchorY).roundToInt(),
  )
}
