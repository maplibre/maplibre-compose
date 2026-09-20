package org.maplibre.compose.demoapp.demos.featureediting

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.maplibre.compose.editing.DrawShape
import org.maplibre.compose.editing.EditStep
import org.maplibre.compose.editing.EditorDraft
import org.maplibre.compose.editing.EditorEvent
import org.maplibre.compose.editing.EditorHandle
import org.maplibre.compose.editing.EditorTool
import org.maplibre.compose.editing.FeatureEditorState
import org.maplibre.compose.editing.HandleHit
import org.maplibre.compose.editing.HandleKind
import org.maplibre.compose.editing.VertexRef
import org.maplibre.compose.editing.handleTarget
import org.maplibre.compose.interaction.PointerButton
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.FeatureId
import org.maplibre.spatialk.geojson.Position
import org.maplibre.spatialk.turf.measurement.bearingTo
import org.maplibre.spatialk.turf.measurement.distance
import org.maplibre.spatialk.turf.measurement.offset
import org.maplibre.spatialk.turf.transformation.circle
import org.maplibre.spatialk.units.Bearing
import org.maplibre.spatialk.units.extensions.meters

/** The radius a touch tap gives its preview circle before the user drags the edge. */
private val TouchPreviewRadius = 80.dp

/**
 * Draws a circle as a turf point buffer. The draft is a one-position line whose cursor is the
 * radius point, so the draft layers draw the radius spoke. A mouse places the centre and clicks the
 * radius, or drags a circle out on the empty map. Touch taps the centre, then drags the edge or
 * taps to set the radius.
 */
internal class DrawCircleTool(
  private val demo: FeatureEditingState,
  private val nextTool: EditorTool,
) : EditorTool {
  // True from a mouse press on the empty map until that pointer lifts: a drag draws the circle,
  // a click only places the centre.
  private var dragOut = false

  override fun handles(state: FeatureEditorState): List<EditorHandle> {
    val draft = state.draft ?: return emptyList()
    val center = draft.positions.firstOrNull() ?: return emptyList()
    val handles = mutableListOf(centerHandle(center))
    draft.cursor?.let { handles += EditorHandle(FrameHandle.Radius, null, it) }
    return handles
  }

  override fun onEvent(event: EditorEvent, state: FeatureEditorState): Boolean =
    when (event) {
      is EditorEvent.Press -> onPress(event, state)
      is EditorEvent.Drag -> onDrag(event, state)
      is EditorEvent.Release -> onRelease(state)
      is EditorEvent.Tap -> onTap(event, state)
      is EditorEvent.Hover -> {
        state.draft?.let { state.draft = it.copy(cursor = event.pointer.position) }
        false
      }
      is EditorEvent.Cancel -> {
        if (dragOut) state.cancelDraft()
        dragOut = false
        state.activeHandle = null
        false
      }
      is EditorEvent.Key -> onKey(event, state)
      else -> false
    }

  override fun cursor(state: FeatureEditorState): PointerIcon = PointerIcon.Crosshair

  /** Places the centre with a preview radius, as a touch tap does. */
  override fun placeDraftPosition(state: FeatureEditorState, position: Position): Boolean {
    val radius = (TouchPreviewRadius.value * demo.metersPerDp).meters
    state.draft =
      EditorDraft(
        DrawShape.LineString,
        listOf(position),
        cursor = position.offset(radius, Bearing.East),
      )
    return true
  }

  override fun canFinishDraft(state: FeatureEditorState): Boolean {
    val draft = state.draft ?: return false
    val center = draft.positions.singleOrNull() ?: return false
    val cursor = draft.cursor ?: return false
    return draft.shape == DrawShape.LineString && distance(center, cursor) >= 1.meters
  }

  override fun finishDraft(state: FeatureEditorState): FeatureId? {
    if (!canFinishDraft(state)) return null
    val draft = checkNotNull(state.draft)
    val center = draft.positions.single()
    val radius = distance(center, checkNotNull(draft.cursor))
    val polygon = circle(center, radius, steps = CIRCLE_STEPS)
    val feature = Feature(polygon, buildJsonObject { put(SHAPE_PROPERTY, SHAPE_CIRCLE) })
    val id = state.update(listOf(feature), undoStep = EditStep())?.single() ?: return null
    state.draft = null
    state.selection = setOf(id)
    state.tool = nextTool
    return id
  }

  private fun onPress(event: EditorEvent.Press, state: FeatureEditorState): Boolean {
    dragOut = false
    val hit = event.hit
    if (hit is HandleHit && (hit.handle.kind == FrameHandle.Radius || hit.handle.isCenter)) {
      if (event.pointer.buttons.any { it != PointerButton.Primary }) return false
      state.activeHandle = hit.handle
      return true
    }
    val pointer = event.pointer
    if (
      hit == null &&
        state.draft == null &&
        pointer.pointerType == PointerType.Mouse &&
        PointerButton.Primary in pointer.buttons
    ) {
      state.draft = EditorDraft(DrawShape.LineString, listOf(pointer.position))
      state.activeHandle = EditorHandle(FrameHandle.Radius, null, pointer.position)
      dragOut = true
      return true
    }
    return false
  }

  private fun onDrag(event: EditorEvent.Drag, state: FeatureEditorState): Boolean {
    val draft = state.draft ?: return false
    val active = state.activeHandle ?: return false
    val center = draft.positions.firstOrNull() ?: return false
    // A drag out of nothing has no handle under the pointer; the radius is the pointer itself.
    val p = event.handleTarget ?: event.pointer.position
    if (active.kind == FrameHandle.Radius) {
      state.draft = draft.copy(cursor = p)
    } else {
      val cursor = draft.cursor
      val moved = cursor?.let { p.offset(distance(center, it), center.bearingTo(it)) }
      state.draft = draft.copy(positions = listOf(p), cursor = moved)
      state.activeHandle = centerHandle(p)
    }
    return false
  }

  private fun onRelease(state: FeatureEditorState): Boolean {
    val finishing = state.activeHandle?.kind == FrameHandle.Radius
    dragOut = false
    state.activeHandle = null
    if (finishing) state.finishDraft()
    return false
  }

  private fun onTap(event: EditorEvent.Tap, state: FeatureEditorState): Boolean {
    val pointer = event.pointer
    val p = pointer.position
    if (dragOut) {
      // The press already placed the centre; the hover supplies the radius point.
      dragOut = false
      state.activeHandle = null
      state.draft = EditorDraft(DrawShape.LineString, listOf(p))
      return true
    }
    val hit = event.hit
    if (hit is HandleHit && hit.handle.isCenter) return true
    if (pointer.pointerType == PointerType.Mouse && PointerButton.Primary !in pointer.buttons) {
      return false
    }
    val draft = state.draft
    if (draft == null) {
      state.activeHandle = null
      if (pointer.pointerType == PointerType.Mouse) {
        state.draft = EditorDraft(DrawShape.LineString, listOf(p))
      } else {
        placeDraftPosition(state, p)
      }
      return true
    }
    state.activeHandle = null
    state.draft = draft.copy(cursor = p)
    if (pointer.pointerType == PointerType.Mouse) state.finishDraft()
    return true
  }

  private fun onKey(event: EditorEvent.Key, state: FeatureEditorState): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    val hasDraft = state.draft != null
    when (event.key) {
      Key.Enter -> state.finishDraft()
      Key.Escape,
      Key.Backspace -> state.cancelDraft()
      else -> return false
    }
    return hasDraft
  }

  private fun centerHandle(center: Position): EditorHandle =
    EditorHandle(HandleKind.Vertex, VertexRef(null, listOf(0)), center)

  private val EditorHandle.isCenter: Boolean
    get() = kind == HandleKind.Vertex && vertex?.featureId == null && vertex != null
}
