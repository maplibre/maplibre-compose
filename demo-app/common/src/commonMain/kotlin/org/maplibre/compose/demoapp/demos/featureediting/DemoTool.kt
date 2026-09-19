package org.maplibre.compose.demoapp.demos.featureediting

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import kotlin.math.sqrt
import org.maplibre.compose.editing.EditorEvent
import org.maplibre.compose.editing.EditorPointer
import org.maplibre.compose.editing.EditorTool
import org.maplibre.compose.editing.FeatureEditorState
import org.maplibre.compose.editing.FeatureHit
import org.maplibre.compose.editing.VertexRef
import org.maplibre.compose.editing.contains
import org.maplibre.compose.interaction.KeyModifier
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.FeatureId
import org.maplibre.spatialk.geojson.Geometry
import org.maplibre.spatialk.geojson.GeometryCollection
import org.maplibre.spatialk.geojson.LineString
import org.maplibre.spatialk.geojson.MultiLineString
import org.maplibre.spatialk.geojson.MultiPoint
import org.maplibre.spatialk.geojson.MultiPolygon
import org.maplibre.spatialk.geojson.Point
import org.maplibre.spatialk.geojson.Polygon
import org.maplibre.spatialk.geojson.Position

/**
 * Wraps every tool of the demo: records the pointer type, switches tools on the digit keys, and
 * snaps taps, drags and draft hovers to nearby vertices of other shapes.
 */
internal class DemoTool(private val demo: FeatureEditingState, private val inner: EditorTool) :
  EditorTool by inner {

  override fun onEvent(event: EditorEvent, state: FeatureEditorState): Boolean {
    pointerOf(event)?.let { demo.lastPointerType = it.pointerType }
    if (
      event is EditorEvent.Key && event.type == KeyEventType.KeyDown && event.modifierKeys.isEmpty()
    ) {
      val tool =
        when (event.key) {
          Key.One -> demo.selectTool
          Key.Two -> demo.polygonTool
          Key.Three -> demo.lineTool
          Key.Four -> demo.circleTool
          else -> null
        }
      if (tool != null) {
        demo.use(tool)
        return true
      }
    }
    val snapped = snap(event, state)
    when (event) {
      is EditorEvent.Press,
      is EditorEvent.Release,
      is EditorEvent.Cancel -> demo.snapTarget = null
      else -> Unit
    }
    return inner.onEvent(snapped, state)
  }

  private fun pointerOf(event: EditorEvent): EditorPointer? =
    when (event) {
      is EditorEvent.Press -> event.pointer
      is EditorEvent.Drag -> event.pointer
      is EditorEvent.Release -> event.pointer
      is EditorEvent.Tap -> event.pointer
      is EditorEvent.LongPress -> event.pointer
      is EditorEvent.Hover -> event.pointer
      else -> null
    }

  private fun snap(event: EditorEvent, state: FeatureEditorState): EditorEvent {
    val pointer =
      when (event) {
        is EditorEvent.Tap -> event.pointer
        is EditorEvent.Drag -> event.pointer
        is EditorEvent.Hover -> if (state.draft != null) event.pointer else null
        else -> null
      }
    if (pointer == null) {
      if (event is EditorEvent.Hover) demo.snapTarget = null
      return event
    }
    if (!demo.snapping || KeyModifier.Alt in pointer.modifierKeys || demo.frameGesture != null) {
      demo.snapTarget = null
      return event
    }
    val excludedFeatures =
      if (event is EditorEvent.Drag && event.hit is FeatureHit && state.activeHandle == null) {
        state.selection
      } else emptySet()
    val target =
      nearestVertex(
        state = state,
        screen = pointer.screen,
        radius = if (pointer.pointerType == PointerType.Mouse) MouseSnapRadius else TouchSnapRadius,
        project = event.project,
        unproject = event.unproject,
        excludedVertex = state.activeHandle?.vertex,
        excludedFeatures = excludedFeatures,
      )
    demo.snapTarget = target
    if (target == null) return event
    val snappedPointer =
      pointer.copy(position = target, screen = event.project(target) ?: pointer.screen)
    return when (event) {
      is EditorEvent.Tap -> event.copy(pointer = snappedPointer)
      is EditorEvent.Drag -> event.copy(pointer = snappedPointer)
      is EditorEvent.Hover -> event.copy(pointer = snappedPointer)
      else -> event
    }
  }

  private fun nearestVertex(
    state: FeatureEditorState,
    screen: DpOffset,
    radius: Dp,
    project: (Position) -> DpOffset?,
    unproject: (DpOffset) -> Position?,
    excludedVertex: VertexRef?,
    excludedFeatures: Set<FeatureId>,
  ): Position? {
    val corners =
      listOf(
        unproject(DpOffset(screen.x - radius, screen.y - radius)),
        unproject(DpOffset(screen.x + radius, screen.y - radius)),
        unproject(DpOffset(screen.x - radius, screen.y + radius)),
        unproject(DpOffset(screen.x + radius, screen.y + radius)),
      )
    if (corners.any { it == null }) return null
    val box =
      BoundingBox(
        corners.minOf { it!!.longitude },
        corners.minOf { it!!.latitude },
        corners.maxOf { it!!.longitude },
        corners.maxOf { it!!.latitude },
      )
    val visible = state.visibleBounds
    var best: Position? = null
    var bestDistance = radius.value
    for (feature in state.features) {
      val id = feature.id ?: continue
      if (id in excludedFeatures) continue
      feature.geometry.forEachVertex { path, position ->
        if (
          excludedVertex != null && excludedVertex.featureId == id && excludedVertex.path == path
        ) {
          return@forEachVertex
        }
        if (position !in box) return@forEachVertex
        if (visible != null && position !in visible) return@forEachVertex
        val projected = project(position) ?: return@forEachVertex
        val dx = (projected.x - screen.x).value
        val dy = (projected.y - screen.y).value
        val distance = sqrt(dx * dx + dy * dy)
        if (distance <= bestDistance) {
          bestDistance = distance
          best = position
        }
      }
    }
    return best
  }
}

private val MouseSnapRadius = 12.dp
private val TouchSnapRadius = 16.dp

/** Visits every distinct vertex with its path, skipping ring closures. */
private inline fun Geometry.forEachVertex(visit: (path: List<Int>, position: Position) -> Unit) {
  when (this) {
    is Point -> visit(emptyList(), coordinates)
    is MultiPoint -> coordinates.forEachIndexed { i, p -> visit(listOf(i), p) }
    is LineString -> coordinates.forEachIndexed { i, p -> visit(listOf(i), p) }
    is MultiLineString ->
      coordinates.forEachIndexed { part, line ->
        line.forEachIndexed { i, p -> visit(listOf(part, i), p) }
      }
    is Polygon ->
      coordinates.forEachIndexed { ring, positions ->
        for (i in 0 until positions.size - 1) visit(listOf(ring, i), positions[i])
      }
    is MultiPolygon ->
      coordinates.forEachIndexed { part, rings ->
        rings.forEachIndexed { ring, positions ->
          for (i in 0 until positions.size - 1) visit(listOf(part, ring, i), positions[i])
        }
      }
    is GeometryCollection<*> -> Unit
  }
}
