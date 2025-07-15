package org.maplibre.compose.material3.util

import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout

/** set padding proportional to the composable's size */
internal fun Modifier.proportionalPadding(
  start: Float = 0f,
  top: Float = 0f,
  end: Float = 0f,
  bottom: Float = 0f,
) = layout { measurable, constraints ->
  val placeable = measurable.measure(constraints)
  val width = placeable.width
  val height = placeable.height
  val startPad = (start * width).toInt()
  val endPad = (end * width).toInt()
  val topPad = (top * height).toInt()
  val bottomPad = (bottom * height).toInt()
  layout(width = width + startPad + endPad, height = height + topPad + bottomPad) {
    placeable.placeRelative(x = startPad, y = topPad)
  }
}

/** set padding proportional to the composable's size */
internal fun Modifier.proportionalPadding(all: Float = 0f) = proportionalPadding(all, all, all, all)

/** set absolute offset proportional to the composable's size */
internal fun Modifier.proportionalAbsoluteOffset(x: Float = 0f, y: Float = 0f) =
  layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val width = placeable.width
    val height = placeable.height
    layout(width, height) { placeable.place(x = (x * width).toInt(), y = (y * height).toInt()) }
  }
