package org.maplibre.compose.demoapp.demos.featureediting

import androidx.compose.runtime.derivedStateOf
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
import org.maplibre.spatialk.geojson.Polygon
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

/**
 * A drag on a frame handle, from press to release. [handleOrigin] is where the handle was pressed
 * and [handle] where the pointer's travel has taken it; the transforms compare the two, so a press
 * off the handle's centre does not jump it.
 */
internal data class FrameGesture(
  val kind: FrameHandle,
  val step: EditStep,
  val id: FeatureId,
  val center: Position,
  val origin: EditorPointer,
  val pointer: EditorPointer,
  val handleOrigin: Position,
  val handle: Position,
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

  /**
   * A shape may cross itself while a pointer drags it, so the drag follows the pointer and the
   * shape shows the problem; [settleGesture] takes the shape back to its last valid frame on
   * release. Every other change is rejected outright.
   */
  val editor: FeatureEditorState =
    FeatureEditorState(
        initialFeatures = listOf(Presets.goldenGatePark),
        initialTool = selectTool,
        validate = { if (dragging) null else validateShape(it) },
        newId = { JsonPrimitive(nextId++) },
      )
      .apply { selection = setOf(checkNotNull(Presets.goldenGatePark.id)) }

  /** True from a press a tool claimed until that pointer is released or cancelled. */
  var dragging by mutableStateOf(false)
    private set

  /** The selected shapes as of the latest drag frame that left all of them valid. */
  private var validFrame: Pair<EditStep, List<EditorFeature>>? = null

  fun startGesture() {
    dragging = true
  }

  private val liveProblem by derivedStateOf { selected.firstNotNullOfOrNull(::validateShape) }

  /** Why the current shape or draft is not acceptable, or null. */
  val problem: String?
    get() = editor.validationError ?: liveProblem

  /** Remembers the selected shapes after a drag frame when none of them crosses itself. */
  fun recordDragFrame(step: EditStep) {
    val shapes = selected
    if (shapes.all { validateShape(it) == null }) validFrame = step to shapes
  }

  /** Ends a drag: a shape left crossing itself goes back to the last valid frame of [step]. */
  fun settleGesture(step: EditStep) {
    dragging = false
    val frame = validFrame?.takeIf { it.first === step }
    validFrame = null
    if (selected.all { validateShape(it) == null }) return
    if (frame != null) editor.update(frame.second, undoStep = step) else editor.revert(step)
  }

  fun cancelGesture() {
    dragging = false
    validFrame = null
  }

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

  /**
   * Switches to [tool]. A frame gesture in progress is taken back, a simplify scrub ends, and a
   * draw tool starts with nothing selected, so no frame competes with it.
   */
  fun use(tool: EditorTool) {
    if (editor.tool !== tool) {
      cancelGesture()
      frameGesture?.let { editor.revert(it.step) }
      frameGesture = null
      simplifyScrub = null
      snapTarget = null
      editor.cancelDraft()
      editor.tool = tool
      if (tool !== selectTool) editor.selection = emptySet()
    }
    mapFocus.requestFocus()
  }

  /** Selects [id] alone with the select tool, whatever tool was active. */
  fun select(id: FeatureId) {
    use(selectTool)
    editor.selection = setOf(id)
  }

  /**
   * Applies simplify slider [value] to the single selected shape, starting a scrub on the first
   * sample. A frame the editor rejects keeps the last accepted one.
   */
  fun scrubSimplify(value: Float) {
    val id = simplifiable?.id ?: return
    // Read live: several samples can arrive before the composition that captured the scrub.
    val current =
      simplifyScrub?.takeIf { it.id == id }
        ?: run {
          val feature = editor.feature(id) ?: return
          SimplifyScrub(EditStep(), id, feature, value, feature).also { simplifyScrub = it }
        }
    current.value = value
    val tolerance = simplifyTolerance(current.original.geometry, value)
    val result = simplified(current.original, tolerance, fallback = current.last)
    if (editor.update(listOf(result), undoStep = current.step) != null) current.last = result
  }

  /** Ends the scrub. A scrub back to zero or one that changed nothing is taken back. */
  fun finishSimplify() {
    val current = simplifyScrub ?: return
    simplifyScrub = null
    val unchanged = editor.feature(current.id)?.geometry == current.original.geometry
    if (current.value == 0f || unchanged) editor.revert(current.step)
    // A rejected last frame leaves its message. A scrub is no claimed gesture and has no draft, so
    // cancelDraft is the one call that clears it without changing anything else.
    if (editor.validationError != null) editor.cancelDraft()
  }

  /** The selected shape the simplify slider applies to: a single polygon or line. */
  val simplifiable: EditorFeature?
    get() =
      selected.singleOrNull()?.takeIf {
        !it.isCircle && (it.geometry is Polygon || it.geometry is LineString)
      }

  fun removeSelection() = remove(editor.selection)

  fun remove(id: FeatureId) = remove(setOf(id))

  private fun remove(ids: Set<FeatureId>) {
    editor.remove(ids)
    mapFocus.requestFocus()
  }

  /**
   * Adds [preset] when its id is absent, selects it, and returns the bounds of the stored feature
   * to fly to.
   */
  fun loadPreset(preset: EditorFeature): BoundingBox {
    val id = checkNotNull(preset.id)
    if (editor.feature(id) == null) editor.update(listOf(preset), undoStep = EditStep())
    select(id)
    return checkNotNull(editor.feature(id)).geometry.computeBbox()
  }

  /**
   * Adds every absent preset as one step, selects the park, and returns the union bounds of the
   * stored features.
   */
  fun loadAllPresets(): BoundingBox {
    val absent = Presets.all.filter { editor.feature(checkNotNull(it.id)) == null }
    if (absent.isNotEmpty()) editor.update(absent, undoStep = EditStep())
    select(checkNotNull(Presets.goldenGatePark.id))
    val stored = Presets.all.map { checkNotNull(editor.feature(checkNotNull(it.id))).geometry }
    return checkNotNull(stored.unionBbox())
  }
}
