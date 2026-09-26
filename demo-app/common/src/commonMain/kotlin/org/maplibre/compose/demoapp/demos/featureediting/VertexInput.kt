package org.maplibre.compose.demoapp.demos.featureediting

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import org.maplibre.compose.map.MapState

/** Only vertex presses belong to the editor; the map keeps all other gestures. */
internal fun Modifier.vertexInput(editor: FeatureEditingState, map: MapState): Modifier =
  pointerInput(editor, map) {
    try {
      awaitEachGesture {
        val down = awaitFirstDown(pass = PointerEventPass.Initial)
        if (
          editor.busy || (down.type == PointerType.Mouse && !currentEvent.buttons.isPrimaryPressed)
        )
          return@awaitEachGesture
        val radius = (if (down.type == PointerType.Mouse) 12.dp else 24.dp).toPx()
        val hits =
          editor.shape.vertices.mapIndexedNotNull { index, position ->
            map.screenLocationFromPosition(position)?.let { screen ->
              val pixels = Offset(screen.x.toPx(), screen.y.toPx())
              Triple(index, screen, (pixels - down.position).getDistance())
            }
          }
        val hit =
          hits.filter { it.third <= radius }.minByOrNull { it.third } ?: return@awaitEachGesture
        down.consume()
        editor.selectedVertex = hit.first
        var dragging = false
        var released = false
        try {
          while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (event.changes.count { it.pressed } > 1 || change.isConsumed) break
            change.consume()
            val travel = change.position - down.position
            if (!dragging && travel.getDistance() > viewConfiguration.touchSlop) {
              dragging = true
              editor.beginEdit()
            }
            if (dragging) {
              // Keep the initial pointer-to-handle offset, even when the press is off center.
              val target = hit.second + DpOffset(travel.x.toDp(), travel.y.toDp())
              val position = map.positionFromScreenLocation(target) ?: break
              editor.moveVertex(hit.first, position)
            }
            if (!change.pressed) {
              released = true
              break
            }
          }
        } finally {
          if (dragging) {
            if (released) editor.finishDrag() else editor.cancel()
          }
        }
      }
    } finally {
      if (editor.preview != null) editor.cancel()
    }
  }
