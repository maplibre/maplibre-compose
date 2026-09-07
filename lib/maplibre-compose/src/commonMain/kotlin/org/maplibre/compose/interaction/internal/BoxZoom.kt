package org.maplibre.compose.interaction.internal

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp

internal class BoxZoomPreview {
  private var origin: DpOffset? = null
  var bounds: DpRect? by mutableStateOf(null)
    private set

  fun start(origin: DpOffset, current: DpOffset) {
    this.origin = origin
    move(current)
  }

  fun move(current: DpOffset) {
    val start = origin ?: return
    bounds =
      DpRect(
        minOf(start.x, current.x),
        minOf(start.y, current.y),
        maxOf(start.x, current.x),
        maxOf(start.y, current.y),
      )
  }

  fun clear(): DpRect? {
    val result = bounds
    origin = null
    bounds = null
    return result
  }
}

internal fun Modifier.drawBoxZoom(preview: BoxZoomPreview): Modifier = drawWithContent {
  drawContent()
  preview.bounds?.let { rect ->
    val topLeft = Offset(rect.left.toPx(), rect.top.toPx())
    val size = Size((rect.right - rect.left).toPx(), (rect.bottom - rect.top).toPx())
    val color = Color(0xff1976d2)
    clipRect {
      drawRect(color.copy(alpha = 0.15f), topLeft, size)
      drawRect(color, topLeft, size, style = Stroke(1.dp.toPx()))
    }
  }
}
