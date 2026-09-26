package org.maplibre.compose.demoapp.demos.featureediting

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.maplibre.spatialk.geojson.Position
import org.maplibre.spatialk.turf.measurement.computeBbox

/** Previews never enter history. Accepting a draw, drag, or simplification records one shape. */
internal class FeatureEditingState(initial: Shape = Shape.of(Presets.goldenGatePark.geometry)) {
  var shape by mutableStateOf(initial)
    private set

  var draft by mutableStateOf<Shape?>(null)
    private set

  var preview by mutableStateOf<Shape?>(null)
    private set

  var selectedVertex by mutableStateOf<Int?>(null)
  var message by mutableStateOf<String?>(null)
    private set

  var simplifying by mutableStateOf(false)
    private set

  private var past by mutableStateOf<List<Shape>>(emptyList())
  private var future by mutableStateOf<List<Shape>>(emptyList())

  val displayed: Shape
    get() = draft ?: preview ?: shape

  val busy: Boolean
    get() = draft != null || preview != null

  val canUndo: Boolean
    get() = !busy && past.isNotEmpty()

  val canRedo: Boolean
    get() = !busy && future.isNotEmpty()

  fun draw(kind: ShapeKind) {
    cancel()
    draft = Shape(kind, emptyList())
  }

  fun place(position: Position) {
    val current = draft ?: return
    draft = current.copy(vertices = current.vertices.plusElement(position))
    message = null
  }

  fun back() {
    draft = draft?.let { it.copy(vertices = it.vertices.dropLast(1)) }
    message = null
  }

  fun beginEdit() {
    simplifying = false
    if (draft == null) preview = shape
    message = null
  }

  fun moveVertex(index: Int, position: Position) {
    preview = preview?.move(index, position)
  }

  fun simplify(amount: Float) {
    simplifying = true
    val bounds = checkNotNull(shape.geometry).computeBbox()
    val tolerance =
      maxOf(bounds.east - bounds.west, bounds.north - bounds.south) * amount * amount * 0.12
    preview = if (amount == 0f) shape else shape.simplified(tolerance)
    selectedVertex = null
    message = null
  }

  fun accept(): Boolean {
    val candidate = draft ?: preview ?: return false
    return replace(candidate)
  }

  /** A rejected drag returns to its starting shape; its explanation remains visible. */
  fun finishDrag() {
    if (!accept()) {
      preview = null
      message = "Edit discarded: ${message.orEmpty()}"
    }
  }

  fun replace(candidate: Shape): Boolean {
    message = candidate.problem()
    if (message != null) return false
    if (candidate != shape) {
      past = (past + shape).takeLast(30)
      future = emptyList()
      shape = candidate
    }
    draft = null
    preview = null
    simplifying = false
    selectedVertex = null
    return true
  }

  fun cancel() {
    draft = null
    preview = null
    simplifying = false
    selectedVertex = null
    message = null
  }

  fun removeVertex() {
    val index = selectedVertex ?: return
    replace(shape.copy(vertices = shape.vertices.filterIndexed { i, _ -> i != index }))
  }

  fun insertVertex(position: Position) {
    val index = selectedVertex ?: return
    replace(
      shape.copy(vertices = shape.vertices.toMutableList().also { it.add(index + 1, position) })
    )
  }

  fun undo() {
    if (!canUndo) return
    future = future + shape
    shape = past.last()
    past = past.dropLast(1)
    cancel()
  }

  fun redo() {
    if (!canRedo) return
    past = past + shape
    shape = future.last()
    future = future.dropLast(1)
    cancel()
  }
}
