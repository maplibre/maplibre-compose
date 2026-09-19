package org.maplibre.compose.editing

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import org.maplibre.compose.editing.internal.forEachSegment
import org.maplibre.compose.editing.internal.forEachVertex
import org.maplibre.compose.editing.internal.isPrimary
import org.maplibre.compose.editing.internal.isSecondary
import org.maplibre.compose.editing.internal.mercatorMidpoint
import org.maplibre.compose.editing.internal.mercatorX
import org.maplibre.compose.editing.internal.mercatorY
import org.maplibre.compose.editing.internal.translatedInMercator
import org.maplibre.compose.editing.internal.wrapMercatorDx
import org.maplibre.compose.interaction.KeyModifier
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.GeometryCollection
import org.maplibre.spatialk.geojson.Position

/**
 * Selects features and edits vertices.
 *
 * Tap on a feature selects it; Shift toggles it in the selection. Tap on empty map clears the
 * selection when [clearSelectionOnEmptyTap]; an empty tap is never consumed, so the map's click
 * callbacks and layer click handlers run. Press on a vertex or midpoint handle claims the pointer;
 * a drag moves the vertex, or inserts one at the midpoint and moves it. A tap on a vertex handle
 * sets [FeatureEditorState.activeHandle]; a tap on a midpoint does nothing. When [moveSelected]
 * returns true for the pointer type, a drag on an already selected feature moves every selected
 * feature in Web Mercator space as one validated step; otherwise a press on a feature pans the map.
 * Delete or Backspace removes the active vertex, else the selected features when
 * [removeSelectionOnDelete]. Arrow keys move the active vertex by [nudgeStep], or by ten times that
 * with Shift, once per key event including repeats. Escape clears the active handle, else the
 * selection. Secondary-button taps on features and, with [removeVertexOnSecondaryClick] false, on
 * vertices are not consumed, so the map's click callbacks see them. A second pointer ends a drag at
 * its last position. Cancel reverts the gesture's changes and clears the active handle.
 *
 * @param canSelect Features for which taps select and handles appear.
 * @param moveSelected Pointer types for which dragging a selected feature moves the selection.
 *   Touch pans by default.
 * @param editVertices Features whose vertex handles appear when selected.
 * @param midpoints Features whose midpoint handles appear when selected.
 * @param removeVertexOnSecondaryClick Whether a secondary mouse click on a vertex removes it.
 * @param removeSelectionOnDelete Whether Delete and Backspace remove the selected features when no
 *   vertex is active.
 * @param clearSelectionOnEmptyTap Whether a tap on empty map clears the selection.
 * @param nudgeStep Screen distance an arrow key moves the active vertex, or null to leave arrows to
 *   the map.
 * @param handleLimit A selected feature with more vertices inside
 *   [FeatureEditorState.visibleBounds] than this gets no handles.
 */
public class SelectTool(
  public val canSelect: (EditorFeature) -> Boolean = { true },
  public val moveSelected: (PointerType) -> Boolean = { it != PointerType.Touch },
  public val editVertices: (EditorFeature) -> Boolean = { true },
  public val midpoints: (EditorFeature) -> Boolean = { true },
  public val removeVertexOnSecondaryClick: Boolean = true,
  public val removeSelectionOnDelete: Boolean = true,
  public val clearSelectionOnEmptyTap: Boolean = true,
  public val nudgeStep: Dp? = 1.dp,
  public val handleLimit: Int = 5000,
) : EditorTool {

  override fun handles(state: FeatureEditorState): List<EditorHandle> {
    val selection = state.selection
    if (selection.isEmpty()) return emptyList()
    val bounds = state.visibleBounds
    val handles = ArrayList<EditorHandle>()
    for (feature in state.features) {
      if (feature.id !in selection || !canSelect(feature)) continue
      val geometry = feature.geometry
      if (geometry is GeometryCollection<*>) continue
      var visible = 0
      geometry.forEachVertex { _, position -> if (bounds.covers(position)) visible++ }
      if (visible > handleLimit) continue
      if (editVertices(feature)) {
        geometry.forEachVertex { path, position ->
          if (bounds.covers(position)) {
            handles += EditorHandle(HandleKind.Vertex, VertexRef(feature.id, path), position)
          }
        }
      }
      if (midpoints(feature)) {
        geometry.forEachSegment { startPath, start, end ->
          val midpoint = mercatorMidpoint(start, end)
          if (bounds.covers(midpoint)) {
            val insertion = startPath.toMutableList().also { it[it.lastIndex]++ }
            handles += EditorHandle(HandleKind.Midpoint, VertexRef(feature.id, insertion), midpoint)
          }
        }
      }
    }
    return handles
  }

  override fun onEvent(event: EditorEvent, state: FeatureEditorState): Boolean =
    when (event) {
      is EditorEvent.Press -> onPress(event, state)
      is EditorEvent.Drag -> onDrag(event, state)
      is EditorEvent.Tap -> onTap(event, state)
      is EditorEvent.Cancel -> {
        state.revert(event.step)
        state.activeHandle = null
        false
      }
      is EditorEvent.Key -> onKey(event, state)
      else -> false
    }

  override fun cursor(state: FeatureEditorState): PointerIcon =
    when (val hover = state.hover) {
      is HandleHit -> PointerIcon.Hand
      is FeatureHit ->
        if (state.feature(hover.featureId)?.let(canSelect) == true) PointerIcon.Hand
        else PointerIcon.Default
      null -> PointerIcon.Default
    }

  private fun onPress(event: EditorEvent.Press, state: FeatureEditorState): Boolean {
    val pointer = event.pointer
    return when (val hit = event.hit) {
      is HandleHit -> {
        val handle = hit.handle
        if (!isSelectableFeatureHandle(handle, state)) return false
        if (pointer.isSecondary && !removesOnSecondaryClick(handle)) return false
        state.activeHandle = handle
        true
      }
      is FeatureHit ->
        moveSelected(pointer.pointerType) && hit.featureId in state.selection && pointer.isPrimary
      null -> false
    }
  }

  private fun onDrag(event: EditorEvent.Drag, state: FeatureEditorState): Boolean {
    val pointer = event.pointer
    return when (event.hit) {
      is HandleHit -> {
        if (pointer.isSecondary) return false
        val handle = state.activeHandle ?: return false
        val ref = handle.vertex ?: return false
        if (handle.kind == HandleKind.Midpoint) {
          if (!state.insertVertex(ref, pointer.position, event.step)) return false
          state.activeHandle = EditorHandle(HandleKind.Vertex, ref, pointer.position)
          true
        } else {
          state.moveVertex(ref, pointer.position, event.step)
        }
      }
      is FeatureHit -> {
        val origin = event.origin.position
        val dx = wrapMercatorDx(mercatorX(pointer.position.longitude) - mercatorX(origin.longitude))
        val dy = mercatorY(pointer.position.latitude) - mercatorY(origin.latitude)
        val moved =
          state.selection.mapNotNull { id ->
            state.featureBefore(event.step, id)?.let { feature ->
              feature.copy(
                geometry = feature.geometry.mapPositions { it.translatedInMercator(dx, dy) }
              )
            }
          }
        state.update(moved, undoStep = event.step) != null
      }
      null -> false
    }
  }

  private fun onTap(event: EditorEvent.Tap, state: FeatureEditorState): Boolean {
    val pointer = event.pointer
    return when (val hit = event.hit) {
      is HandleHit -> {
        val handle = hit.handle
        if (pointer.isSecondary) {
          val ref = handle.vertex
          if (ref == null || !removesOnSecondaryClick(handle)) return false
          state.removeVertex(ref)
          return true
        }
        if (!pointer.isPrimary) return false
        state.activeHandle = if (handle.kind == HandleKind.Midpoint) null else handle
        true
      }
      is FeatureHit -> {
        if (!pointer.isPrimary) return false
        val feature = state.feature(hit.featureId) ?: return false
        if (!canSelect(feature)) return false
        val id = hit.featureId
        val selection = state.selection
        state.selection =
          when {
            KeyModifier.Shift !in pointer.modifierKeys -> setOf(id)
            id in selection -> selection - id
            else -> selection + id
          }
        true
      }
      null -> {
        if (clearSelectionOnEmptyTap) {
          state.activeHandle = null
          state.selection = emptySet()
        }
        false
      }
    }
  }

  private fun onKey(event: EditorEvent.Key, state: FeatureEditorState): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    return when (event.key) {
      Key.Delete,
      Key.Backspace -> {
        val ref = state.activeHandle?.takeIf { it.kind != HandleKind.Midpoint }?.vertex
        when {
          ref != null -> state.removeVertex(ref)
          removeSelectionOnDelete && state.selection.isNotEmpty() -> {
            state.remove(state.selection)
            true
          }
          else -> false
        }
      }
      Key.DirectionLeft -> nudge(event, state, -1f, 0f)
      Key.DirectionRight -> nudge(event, state, 1f, 0f)
      Key.DirectionUp -> nudge(event, state, 0f, -1f)
      Key.DirectionDown -> nudge(event, state, 0f, 1f)
      Key.Escape ->
        when {
          state.activeHandle != null -> {
            state.activeHandle = null
            true
          }
          state.selection.isNotEmpty() -> {
            state.selection = emptySet()
            true
          }
          else -> false
        }
      else -> false
    }
  }

  private fun nudge(
    event: EditorEvent.Key,
    state: FeatureEditorState,
    dx: Float,
    dy: Float,
  ): Boolean {
    val step = nudgeStep ?: return false
    val handle = state.activeHandle ?: return false
    if (handle.kind == HandleKind.Midpoint) return false
    val ref = handle.vertex ?: return false
    val current = state.vertexPosition(ref) ?: return false
    val distance = if (KeyModifier.Shift in event.modifierKeys) step * 10 else step
    val screen = event.project(current) ?: return false
    val target =
      event.unproject(DpOffset(screen.x + distance * dx, screen.y + distance * dy)) ?: return false
    return state.moveVertex(ref, target)
  }

  private fun isSelectableFeatureHandle(handle: EditorHandle, state: FeatureEditorState): Boolean {
    val id = handle.vertex?.featureId ?: return false
    return state.feature(id)?.let(canSelect) == true
  }

  private fun removesOnSecondaryClick(handle: EditorHandle): Boolean =
    handle.kind == HandleKind.Vertex && removeVertexOnSecondaryClick
}

private fun BoundingBox?.covers(position: Position): Boolean = this == null || contains(position)
