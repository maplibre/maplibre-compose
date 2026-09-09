package org.maplibre.compose.demoapp.demos.editablemarkers

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import org.maplibre.spatialk.geojson.Position

internal class EditableMarker(val id: Int, position: Position, label: String) {
  var position by mutableStateOf(position)
  var label by mutableStateOf(label)
  var removing by mutableStateOf(false)
  var bounce by mutableStateOf(0)
  var color by mutableStateOf(MarkerColor.Theme)
}

internal class EditableMarkersState {
  val markers = mutableStateListOf<EditableMarker>()
  private var nextId = 1
  var textScale by mutableStateOf(1f)

  // Editing and dragging are separate: moving a pin must not open its editor.
  var editingId by mutableStateOf<Int?>(null)
  var draggingId by mutableStateOf<Int?>(null)
  var hoveredId by mutableStateOf<Int?>(null)
  var pressedId by mutableStateOf<Int?>(null)
  var dragTilt by mutableStateOf(0f)
  var overTrash by mutableStateOf(false)
  var trashNeedsExit by mutableStateOf(false)

  var trashBounds: Rect? = null

  fun select(marker: EditableMarker) {
    if (marker.removing) return
    editingId = marker.id
    marker.bounce++
  }

  fun remove(marker: EditableMarker) {
    if (marker.removing) return
    marker.removing = true
    if (editingId == marker.id) editingId = null
    if (hoveredId == marker.id) hoveredId = null
  }

  fun endGesture() {
    pressedId = null
    draggingId = null
    overTrash = false
    trashNeedsExit = false
    dragTilt = 0f
  }

  fun add(position: Position) {
    val marker = EditableMarker(nextId++, position, "New place")
    markers.add(marker)
    editingId = marker.id
  }
}
