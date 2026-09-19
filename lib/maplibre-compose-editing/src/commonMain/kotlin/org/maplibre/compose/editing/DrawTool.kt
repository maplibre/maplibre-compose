package org.maplibre.compose.editing

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.PointerType
import kotlinx.serialization.json.JsonObject
import org.maplibre.compose.editing.internal.isPrimary
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.FeatureId
import org.maplibre.spatialk.geojson.Geometry
import org.maplibre.spatialk.geojson.LineString
import org.maplibre.spatialk.geojson.Point
import org.maplibre.spatialk.geojson.Polygon
import org.maplibre.spatialk.geojson.Position

/**
 * Draws a feature of [shape].
 *
 * With [placeOnTap], a primary-button or touch tap places a vertex; without it taps are consumed
 * and do nothing, and positions arrive through [FeatureEditorState.placeDraftPosition]. Taps and
 * presses with another button are not consumed, so the map's click callbacks see them. Point
 * creates a feature on the first placed position. LineString finishes on Enter with at least two
 * positions, on a double tap when [finishOnDoubleTap], and on a tap on its last vertex when
 * [finishOnVertexTap]. Polygon finishes the same way with at least three distinct positions, with
 * the first vertex as the tap target; the ring is closed on finish. Rectangle finishes on the
 * second tap, or with [placeOnTap] on release of a primary-button mouse drag, as a Polygon aligned
 * to longitude and latitude. A finishing double tap removes the vertex its first tap placed; a
 * double tap on an existing vertex removes none. Backspace removes the last position. Escape
 * discards the draft. With [draftHandles], draft vertices are handles that a drag moves and that
 * [finishOnVertexTap] targets. A draft of another shape is discarded when the first position is
 * placed. LineString, Polygon and Rectangle finish through [FeatureEditorState.finishDraft]; Point
 * is created inside [placeDraftPosition], and [finishDraft] returns null for it.
 *
 * @param properties Properties of created features.
 * @param nextTool Tool activated after a feature is created, with the feature selected, or null to
 *   keep drawing.
 * @param finishOnDoubleTap Whether a double tap finishes a line or polygon.
 * @param finishOnVertexTap Whether a tap on the first polygon vertex or last line vertex finishes.
 * @param replaceExisting Whether finishing removes every other feature in the same undo step.
 * @param placeOnTap Whether a tap on the map places a vertex.
 * @param draftHandles Whether draft vertices are handles.
 */
public class DrawTool(
  public val shape: DrawShape,
  public val properties: JsonObject? = null,
  public val nextTool: EditorTool? = SelectTool(),
  public val finishOnDoubleTap: Boolean = true,
  public val finishOnVertexTap: Boolean = true,
  public val replaceExisting: Boolean = false,
  public val placeOnTap: Boolean = true,
  public val draftHandles: Boolean = true,
) : EditorTool {
  // Whether the latest tap placed a position, so a finishing double tap removes only that one.
  private var lastTapPlaced = false

  override fun handles(state: FeatureEditorState): List<EditorHandle> {
    if (!draftHandles) return emptyList()
    val draft = state.draft ?: return emptyList()
    return draft.positions.mapIndexed { i, position ->
      EditorHandle(HandleKind.Vertex, VertexRef(null, listOf(i)), position)
    }
  }

  override fun onEvent(event: EditorEvent, state: FeatureEditorState): Boolean =
    when (event) {
      is EditorEvent.Press -> onPress(event, state)
      is EditorEvent.Drag -> onDrag(event, state)
      is EditorEvent.Release -> onRelease(event, state)
      is EditorEvent.Tap -> onTap(event, state)
      is EditorEvent.Hover -> {
        state.draft?.let { state.draft = it.copy(cursor = event.pointer.position) }
        false
      }
      is EditorEvent.HoverEnd -> {
        state.draft?.let { if (it.cursor != null) state.draft = it.copy(cursor = null) }
        false
      }
      is EditorEvent.Cancel -> onCancel(state)
      is EditorEvent.Key -> onKey(event, state)
      else -> false
    }

  override fun cursor(state: FeatureEditorState): PointerIcon = PointerIcon.Crosshair

  override fun placeDraftPosition(state: FeatureEditorState, position: Position): Boolean {
    lastTapPlaced = false
    val current = state.draft?.takeIf { it.shape == shape }
    if (current == null && state.draft != null) state.draft = null
    if (shape == DrawShape.Point) {
      commit(state, Point(position))
      return true
    }
    val positions =
      when {
        current == null -> listOf(position)
        shape == DrawShape.Rectangle && current.positions.size >= 2 ->
          listOf(current.positions[0], position)
        else -> current.positions.plusElement(position)
      }
    state.draft = EditorDraft(shape, positions, cursor = current?.cursor)
    state.validationError = null
    if (shape == DrawShape.Rectangle && positions.size == 2) state.finishDraft()
    return true
  }

  override fun canFinishDraft(state: FeatureEditorState): Boolean {
    val draft = state.draft ?: return false
    return draft.shape == shape && canFinish(draft.positions)
  }

  override fun finishDraft(state: FeatureEditorState): FeatureId? {
    if (!canFinishDraft(state)) return null
    val positions = checkNotNull(state.draft).positions
    val geometry: Geometry =
      when (shape) {
        DrawShape.LineString -> LineString(positions)
        DrawShape.Polygon -> Polygon(listOf(positions.plusElement(positions.first())))
        DrawShape.Rectangle -> rectangle(positions[0], positions[1])
        DrawShape.Point -> return null
      }
    return commit(state, geometry)
  }

  private fun onPress(event: EditorEvent.Press, state: FeatureEditorState): Boolean {
    val pointer = event.pointer
    if (!pointer.isPrimary) return false
    val hit = event.hit
    if (hit is HandleHit && hit.handle.isDraft) {
      state.activeHandle = hit.handle
      return true
    }
    return hit == null &&
      shape == DrawShape.Rectangle &&
      placeOnTap &&
      pointer.pointerType == PointerType.Mouse &&
      state.draft?.takeIf { it.shape == shape }?.positions.isNullOrEmpty()
  }

  private fun onDrag(event: EditorEvent.Drag, state: FeatureEditorState): Boolean {
    val hit = event.hit
    if (hit is HandleHit && hit.handle.isDraft) {
      hit.handle.vertex?.let { state.moveVertex(it, event.pointer.position) }
    } else if (hit == null && shape == DrawShape.Rectangle) {
      state.draft =
        EditorDraft(
          DrawShape.Rectangle,
          listOf(event.origin.position),
          cursor = event.pointer.position,
        )
    }
    return false
  }

  private fun onRelease(event: EditorEvent.Release, state: FeatureEditorState): Boolean {
    if (isRectangleDrag(state)) state.placeDraftPosition(event.pointer.position)
    return false
  }

  private fun onTap(event: EditorEvent.Tap, state: FeatureEditorState): Boolean {
    if (!event.pointer.isPrimary) return false
    val placed = lastTapPlaced
    lastTapPlaced = false
    if (event.count == 2 && finishOnDoubleTap && placeOnTap) {
      val draft = state.draft?.takeIf { it.shape == shape }
      if (draft != null) {
        val positions = if (placed) draft.positions.dropLast(1) else draft.positions
        if (canFinish(positions)) {
          if (placed) state.removeLastDraftPosition()
          state.finishDraft()
        }
      }
      return true
    }
    val handle = (event.hit as? HandleHit)?.handle?.takeIf { it.isDraft }
    if (handle != null) {
      if (finishOnVertexTap && isFinishTarget(handle, state) && canFinishDraft(state)) {
        state.finishDraft()
      } else {
        state.activeHandle = handle
      }
      return true
    }
    if (placeOnTap) {
      state.placeDraftPosition(event.pointer.position)
      lastTapPlaced = state.draft?.shape == shape
    }
    return true
  }

  private fun onCancel(state: FeatureEditorState): Boolean {
    if (isRectangleDrag(state)) state.cancelDraft()
    return false
  }

  private fun onKey(event: EditorEvent.Key, state: FeatureEditorState): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    val hasDraft = state.draft != null
    when (event.key) {
      Key.Enter -> state.finishDraft()
      Key.Escape -> state.cancelDraft()
      Key.Backspace -> state.removeLastDraftPosition()
      else -> return false
    }
    lastTapPlaced = false
    return hasDraft
  }

  // A rectangle drag is the only claimed press that leaves one position with no active handle: a
  // draft-handle press sets the active handle, and the press claims only on an empty draft.
  private fun isRectangleDrag(state: FeatureEditorState): Boolean {
    if (shape != DrawShape.Rectangle || state.activeHandle != null) return false
    val draft = state.draft ?: return false
    return draft.shape == DrawShape.Rectangle && draft.positions.size == 1
  }

  private fun isFinishTarget(handle: EditorHandle, state: FeatureEditorState): Boolean {
    val index = handle.vertex?.path?.singleOrNull() ?: return false
    val draft = state.draft?.takeIf { it.shape == shape } ?: return false
    return when (shape) {
      DrawShape.Polygon -> index == 0
      DrawShape.LineString -> index == draft.positions.lastIndex
      else -> false
    }
  }

  private fun canFinish(positions: List<Position>): Boolean =
    when (shape) {
      DrawShape.Point -> false
      DrawShape.LineString -> positions.size >= 2
      DrawShape.Polygon -> positions.distinct().size >= 3
      DrawShape.Rectangle -> positions.size == 2
    }

  private fun commit(state: FeatureEditorState, geometry: Geometry): FeatureId? {
    val removeIds =
      if (replaceExisting) state.features.mapNotNull { it.id } else emptyList<FeatureId>()
    val id =
      state
        .update(listOf(Feature(geometry, properties)), removeIds, undoStep = EditStep())
        ?.single() ?: return null
    state.draft = null
    state.selection = setOf(id)
    nextTool?.let { state.tool = it }
    return id
  }

  private fun rectangle(a: Position, b: Position): Polygon {
    val corner = Position(a.longitude, a.latitude)
    return Polygon(
      listOf(
        listOf(
          corner,
          Position(b.longitude, a.latitude),
          Position(b.longitude, b.latitude),
          Position(a.longitude, b.latitude),
          corner,
        )
      )
    )
  }
}

private val EditorHandle.isDraft: Boolean
  get() = vertex != null && vertex.featureId == null
