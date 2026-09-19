package org.maplibre.compose.demoapp.demos.featureediting

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.pointer.PointerType
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.maplibre.compose.editing.DrawShape
import org.maplibre.compose.editing.DrawTool
import org.maplibre.compose.editing.EditStep
import org.maplibre.compose.editing.EditorFeature
import org.maplibre.compose.editing.EditorPointer
import org.maplibre.compose.editing.EditorTool
import org.maplibre.compose.editing.FeatureEditorState
import org.maplibre.compose.editing.SelectTool
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.FeatureId
import org.maplibre.spatialk.geojson.LineString
import org.maplibre.spatialk.geojson.MultiLineString
import org.maplibre.spatialk.geojson.Position
import org.maplibre.spatialk.turf.measurement.computeBbox

internal enum class ShapeKind(val label: String) {
  Polygon("Polygon"),
  Line("Line"),
  Circle("Circle"),
}

internal enum class MeasureUnits(val label: String) {
  Metric("Metric"),
  Imperial("Imperial"),
}

/** A drag on a frame handle, from press to release. */
internal data class FrameGesture(
  val kind: FrameHandle,
  val step: EditStep,
  val id: FeatureId,
  val center: Position,
  val origin: EditorPointer,
  val pointer: EditorPointer,
  val readout: String,
)

/** A simplify slider drag, from the first change to release. */
internal class SimplifyScrub(
  val step: EditStep,
  val id: FeatureId,
  val original: EditorFeature,
  value: Float,
  last: EditorFeature,
) {
  var value by mutableStateOf(value)

  /** The latest result the editor accepted. Rings that would collapse keep its rings. */
  var last by mutableStateOf(last)
}

internal const val NAME_PROPERTY = "name"
internal const val SHAPE_PROPERTY = "shape"
internal const val SHAPE_CIRCLE = "circle"

internal val EditorFeature.isCircle: Boolean
  get() =
    properties?.get(SHAPE_PROPERTY)?.let { (it as? JsonPrimitive)?.contentOrNull } == SHAPE_CIRCLE

internal val EditorFeature.kind: ShapeKind
  get() =
    when {
      isCircle -> ShapeKind.Circle
      geometry is LineString || geometry is MultiLineString -> ShapeKind.Line
      else -> ShapeKind.Polygon
    }

internal val EditorFeature.displayName: String
  get() =
    properties?.get(NAME_PROPERTY)?.let { (it as? JsonPrimitive)?.contentOrNull }
      ?: "${kind.label} ${id?.content}"

/** The demo's state: the editor, its tools, and everything the overlay and panel show. */
internal class FeatureEditingState {
  private var nextId = 1

  val mapFocus = FocusRequester()

  val selectTool: EditorTool =
    DemoTool(
      this,
      FrameTool(this, SelectTool(editVertices = { !it.isCircle }, midpoints = { !it.isCircle })),
    )

  val polygonTool: EditorTool = DemoTool(this, DrawTool(DrawShape.Polygon, nextTool = selectTool))

  val lineTool: EditorTool = DemoTool(this, DrawTool(DrawShape.LineString, nextTool = selectTool))

  val circleTool: EditorTool = DemoTool(this, DrawCircleTool(this, nextTool = selectTool))

  val editor =
    FeatureEditorState(
        initialFeatures = listOf(Presets.goldenGatePark),
        initialTool = selectTool,
        validate = ::validateShape,
        newId = { JsonPrimitive(nextId++) },
      )
      .apply { selection = setOf(checkNotNull(Presets.goldenGatePark.id)) }

  var units by mutableStateOf(MeasureUnits.Metric)

  var snapping by mutableStateOf(true)

  /** The vertex the pointer is snapped to, shown as a ring on the map. */
  var snapTarget: Position? by mutableStateOf(null)

  /**
   * The pointer type of the latest event: picks the hint wording, tooltips and the touch circle
   * preview. Platforms with an undo shortcut have a keyboard, so they start with mouse wording.
   */
  var lastPointerType by
    mutableStateOf(if (undoShortcutHint != null) PointerType.Mouse else PointerType.Touch)

  /** The viewport scale, rounded to three significant digits so panning does not move the frame. */
  var metersPerDp by mutableStateOf(20.0)

  var frameGesture: FrameGesture? by mutableStateOf(null)

  var simplifyScrub: SimplifyScrub? by mutableStateOf(null)

  /** Where the station dot sits along the selected line, 0 to 1. */
  var stationFraction by mutableStateOf(0.5)

  var coachDismissed by mutableStateOf(false)

  val selected: List<EditorFeature>
    get() = editor.features.filter { it.id in editor.selection }

  /** Switches to [tool]. A draw tool starts with nothing selected, so no frame competes with it. */
  fun use(tool: EditorTool) {
    if (editor.tool !== tool) {
      editor.cancelDraft()
      editor.tool = tool
      if (tool !== selectTool) editor.selection = emptySet()
    }
    mapFocus.requestFocus()
  }

  fun select(id: FeatureId) {
    editor.selection = setOf(id)
    mapFocus.requestFocus()
  }

  fun removeSelection() {
    editor.remove(editor.selection)
    mapFocus.requestFocus()
  }

  /** Adds [preset] when its id is absent, selects it, and returns the bounds to fly to. */
  fun loadPreset(preset: EditorFeature): BoundingBox {
    val id = checkNotNull(preset.id)
    if (editor.feature(id) == null) editor.update(listOf(preset), undoStep = EditStep())
    select(id)
    return preset.geometry.computeBbox()
  }

  /** Adds every absent preset as one step, selects the park, and returns their union bounds. */
  fun loadAllPresets(): BoundingBox {
    val absent = Presets.all.filter { editor.feature(checkNotNull(it.id)) == null }
    if (absent.isNotEmpty()) editor.update(absent, undoStep = EditStep())
    select(checkNotNull(Presets.goldenGatePark.id))
    return checkNotNull(Presets.all.map { it.geometry }.unionBbox())
  }
}
