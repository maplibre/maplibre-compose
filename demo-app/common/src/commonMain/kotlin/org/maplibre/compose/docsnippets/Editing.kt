@file:Suppress("unused")
@file:OptIn(FlowPreview::class)

package org.maplibre.compose.docsnippets

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlin.math.hypot
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.maplibre.compose.editing.DrawShape
import org.maplibre.compose.editing.DrawTool
import org.maplibre.compose.editing.EditorColors
import org.maplibre.compose.editing.EditorDraft
import org.maplibre.compose.editing.EditorDraftLayers
import org.maplibre.compose.editing.EditorEvent
import org.maplibre.compose.editing.EditorFeature
import org.maplibre.compose.editing.EditorHandleLayers
import org.maplibre.compose.editing.EditorHit
import org.maplibre.compose.editing.EditorPointer
import org.maplibre.compose.editing.EditorTool
import org.maplibre.compose.editing.FeatureEditorLayers
import org.maplibre.compose.editing.FeatureEditorState
import org.maplibre.compose.editing.FeatureHit
import org.maplibre.compose.editing.SelectTool
import org.maplibre.compose.editing.featureEditor
import org.maplibre.compose.editing.mapPositions
import org.maplibre.compose.editing.rememberFeatureEditorState
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.interaction.ClickResult
import org.maplibre.compose.interaction.MapInteractions
import org.maplibre.compose.layers.Anchor
import org.maplibre.compose.layers.FillLayer
import org.maplibre.compose.layers.LineLayer
import org.maplibre.compose.map.MapUiOptions
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.rememberMapState
import org.maplibre.compose.overlay.MapOverlay
import org.maplibre.compose.overlay.include
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.GeoJsonOptions
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.FeatureId
import org.maplibre.spatialk.geojson.Geometry
import org.maplibre.spatialk.geojson.MultiPolygon
import org.maplibre.spatialk.geojson.Polygon
import org.maplibre.spatialk.geojson.Position
import org.maplibre.spatialk.geojson.toJson
import org.maplibre.spatialk.turf.coordinatemutation.flattenCoordinates
import org.maplibre.spatialk.turf.measurement.area
import org.maplibre.spatialk.turf.measurement.bearingTo
import org.maplibre.spatialk.turf.measurement.center
import org.maplibre.spatialk.turf.measurement.computeBbox
import org.maplibre.spatialk.turf.measurement.distance
import org.maplibre.spatialk.turf.measurement.length
import org.maplibre.spatialk.turf.measurement.offset
import org.maplibre.spatialk.units.extensions.hectares
import org.maplibre.spatialk.units.extensions.inHectares
import org.maplibre.spatialk.units.extensions.kilometers

// #region basic
@Composable
fun DeliveryAreaEditor() {
  val select = remember {
    SelectTool(moveSelected = { false }, clearSelectionOnEmptyTap = false) // (1)!
  }
  val editor = rememberFeatureEditorState(initialTool = select)
  val map = rememberMapState {
    Anchor.Below({ it.type == "symbol" }) { FeatureEditorLayers(editor) } // (2)!
  }
  Column {
    MaplibreMap(
      modifier = Modifier.weight(1f),
      state = map,
      surfaceModifier = Modifier.featureEditor(editor, map), // (3)!
    )
    Row {
      when {
        editor.draft != null ->
          Button(enabled = editor.canFinishDraft, onClick = { editor.finishDraft() }) {
            Text("Done")
          }
        editor.features.isEmpty() ->
          Button(onClick = { editor.tool = DrawTool(DrawShape.Polygon, nextTool = select) }) {
            Text("Outline your delivery area")
          }
        else ->
          Button(
            onClick = {
              editor.tool = DrawTool(DrawShape.Polygon, replaceExisting = true, nextTool = select)
            }
          ) {
            Text("Redraw")
          }
      }
      if (editor.tool is DrawTool) {
        Button(
          onClick = {
            editor.cancelDraft()
            editor.tool = select // (4)!
          }
        ) {
          Text("Cancel")
        }
      }
      Button(enabled = editor.canUndo, onClick = editor::undo) { Text("Undo") }
    }
    editor.features.singleOrNull()?.let { area ->
      Text("${area.geometry.area().inHectares.toInt()} ha")
    }
  }
}

// #endregion basic

// #region save
@Composable
fun SaveOnChange(editor: FeatureEditorState, save: (String) -> Unit) {
  LaunchedEffect(editor) {
    snapshotFlow { editor.features }.collect { save(FeatureCollection(it).toJson()) }
  }
}

// #endregion save

@Composable
fun ThemedEditor(editor: FeatureEditorState) {
  // #region colors
  val map = rememberMapState {
    Anchor.Below({ it.type == "symbol" }) {
      FeatureEditorLayers(
        editor,
        colors = EditorColors(accent = MaterialTheme.colorScheme.primary),
        handleRadius = 8.dp,
      )
    }
  }
  MaplibreMap(state = map, surfaceModifier = Modifier.featureEditor(editor, map))
  // #endregion colors
}

interface GeofenceRepository {
  fun load(): List<EditorFeature>

  suspend fun save(features: List<EditorFeature>)

  val remoteChanges: Flow<Pair<List<EditorFeature>, List<FeatureId>>>
}

// #region viewmodel
class GeofenceViewModel(handle: SavedStateHandle, repository: GeofenceRepository) : ViewModel() {
  val select = SelectTool()
  val editor = FeatureEditorState(initialFeatures = repository.load(), initialTool = select)

  init {
    handle.get<String>("draft")?.let { json -> // (1)!
      val draft = Json.decodeFromString<EditorDraft>(json)
      editor.draft = draft
      editor.tool = DrawTool(draft.shape, nextTool = select)
    }
    viewModelScope.launch {
      snapshotFlow { editor.features }.debounce(500.milliseconds).collect { repository.save(it) }
    }
    viewModelScope.launch {
      snapshotFlow { editor.draft?.copy(cursor = null) } // (2)!
        .debounce(1.seconds)
        .collect { draft -> handle["draft"] = draft?.let { Json.encodeToString(it) } }
    }
  }
}

// #endregion viewmodel

// #region network
@Composable
fun RemoteFeatureEditor(remote: FeatureCollection<Geometry, JsonObject?>?) {
  val editor = rememberFeatureEditorState()
  var loaded by rememberSaveable { mutableStateOf(false) }
  LaunchedEffect(remote) {
    if (!loaded && remote != null) {
      editor.load(remote.features) // (1)!
      loaded = true
    }
  }
  val map = rememberMapState { FeatureEditorLayers(editor) }
  MaplibreMap(state = map, surfaceModifier = Modifier.featureEditor(editor, map))
}

// #endregion network

// #region sync
@Composable
fun MergeRemoteChanges(editor: FeatureEditorState, repository: GeofenceRepository) {
  LaunchedEffect(editor) {
    repository.remoteChanges.collect { (changed, deleted) ->
      editor.update(changed, removeIds = deleted)
    }
  }
}

// #endregion sync

// #region validate
@Composable
fun ValidatedEditor() {
  val editor =
    rememberFeatureEditorState(
      validate = { feature ->
        val geometry = feature.geometry
        when {
          geometry.area() > 50.hectares -> "Larger than 50 hectares"
          geometry.length() > 20.kilometers -> "Longer than 20 km"
          else -> null
        }
      }
    )
  val map = rememberMapState { FeatureEditorLayers(editor) }
  Column {
    MaplibreMap(
      modifier = Modifier.weight(1f),
      state = map,
      surfaceModifier = Modifier.featureEditor(editor, map),
    )
    editor.validationError?.let { Text(it) } // (1)!
  }
}

// #endregion validate

// #region gps
@Composable
fun WalkedPathEditor(fixes: Flow<Position>) {
  val select = remember { SelectTool() }
  val editor = rememberFeatureEditorState(initialTool = select)
  LaunchedEffect(editor) { fixes.collect { fix -> editor.placeDraftPosition(fix) } } // (1)!
  Row {
    Button(
      enabled = editor.draft == null,
      onClick = {
        editor.tool =
          DrawTool(
            DrawShape.LineString,
            nextTool = select,
            finishOnDoubleTap = false,
            finishOnVertexTap = false,
            placeOnTap = false, // (2)!
            draftHandles = false,
          )
      },
    ) {
      Text("Start walking")
    }
    Button(enabled = editor.canFinishDraft, onClick = { editor.finishDraft() }) { Text("Done") }
  }
}

// #endregion gps

// #region delete-vertex
@Composable
fun DeleteVertexButton(editor: FeatureEditorState) {
  val vertex = editor.activeHandle?.vertex // (1)!
  Button(enabled = vertex != null, onClick = { vertex?.let { editor.removeVertex(it) } }) {
    Text("Delete point")
  }
}

// #endregion delete-vertex

interface ParcelStore {
  val parcels: List<EditorFeature>

  fun parcelAt(position: Position): EditorFeature?

  suspend fun create(parcel: EditorFeature): EditorFeature
}

// #region scratch
@Composable
fun ParcelEditor(store: ParcelStore) {
  val editor = rememberFeatureEditorState()
  val map = rememberMapState {
    val editing by
      remember(editor) { derivedStateOf { editor.features.mapTo(HashSet()) { it.id } } }
    val parcels = store.parcels.filter { it.id !in editing } // (1)!
    FillLayer(
      id = "parcels",
      source = rememberGeoJsonSource(GeoJsonData.Features(FeatureCollection(parcels))),
    )
    FeatureEditorLayers(editor)
  }
  val scope = rememberCoroutineScope()
  Column {
    MaplibreMap(
      modifier = Modifier.weight(1f),
      state = map,
      surfaceModifier = Modifier.featureEditor(editor, map),
      interactions =
        MapInteractions {
          callbacks {
            click {
              onUnhandled { event -> // (2)!
                val parcel = event.position?.let(store::parcelAt)
                if (parcel == null) return@onUnhandled ClickResult.Pass
                val ids = editor.update(listOf(parcel)) ?: return@onUnhandled ClickResult.Pass
                editor.selection = ids.toSet()
                ClickResult.Consume
              }
            }
          }
        },
    )
    Button(
      enabled = editor.canFinishDraft,
      onClick = {
        val scratchId = editor.finishDraft() ?: return@Button
        scope.launch {
          val created = store.create(checkNotNull(editor.feature(scratchId)))
          editor.update(listOf(created), removeIds = listOf(scratchId)) // (3)!
        }
      },
    ) {
      Text("Done")
    }
  }
}

// #endregion scratch

// #region commit-points
@Composable
fun SaveCommitPoints(editor: FeatureEditorState, save: suspend (List<EditorFeature>) -> Unit) {
  LaunchedEffect(editor) {
    snapshotFlow { editor.gestureInProgress to editor.features }
      .filter { (inProgress, _) -> !inProgress }
      .map { (_, features) -> features }
      .distinctUntilChanged()
      .collect { save(it) }
  }
}

// #endregion commit-points

// #region snapping
/** Snaps placed and dragged positions to vertices of unselected features within [radius]. */
class SnappingTool(private val inner: EditorTool, private val radius: Dp = 8.dp) :
  EditorTool by inner {
  override fun onEvent(event: EditorEvent, state: FeatureEditorState): Boolean {
    val snapped =
      when (event) {
        is EditorEvent.Drag -> event.copy(pointer = snap(event.pointer, state, event.project))
        is EditorEvent.Tap -> event.copy(pointer = snap(event.pointer, state, event.project))
        else -> event
      }
    return inner.onEvent(snapped, state)
  }

  private fun snap(
    pointer: EditorPointer,
    state: FeatureEditorState,
    project: (Position) -> DpOffset?,
  ): EditorPointer {
    var best: Position? = null
    var bestDistance = radius.value
    for (feature in state.features) {
      if (feature.id in state.selection) continue
      for (vertex in feature.geometry.flattenCoordinates()) {
        val screen = project(vertex) ?: continue
        val distance =
          hypot(screen.x.value - pointer.screen.x.value, screen.y.value - pointer.screen.y.value)
        if (distance < bestDistance) {
          best = vertex
          bestDistance = distance
        }
      }
    }
    return best?.let { pointer.copy(position = it) } ?: pointer
  }
}

// #endregion snapping

// #region geodesic-move
/** Moves selected features along a great circle instead of in Web Mercator space. */
class GeodesicMoveTool(private val inner: SelectTool = SelectTool()) : EditorTool by inner {
  override fun onEvent(event: EditorEvent, state: FeatureEditorState): Boolean {
    if (event !is EditorEvent.Drag || event.hit !is FeatureHit) return inner.onEvent(event, state)
    val origin = event.origin.position
    val distance = distance(origin, event.pointer.position)
    val bearing = origin.bearingTo(event.pointer.position)
    val moved =
      state.selection.mapNotNull { id ->
        state.featureBefore(event.step, id)?.let { feature -> // (1)!
          feature.copy(
            geometry =
              feature.geometry.mapPositions {
                it.offset(distance, bearing) // (2)!
              }
          )
        }
      }
    return state.update(moved, undoStep = event.step) != null
  }
}

// #endregion geodesic-move

// #region hole
fun FeatureEditorState.addHole(id: FeatureId, positions: List<Position>): Boolean {
  val feature = feature(id) ?: return false
  val polygon = feature.geometry as? Polygon ?: return false
  if (positions.size < 3) return false
  val ring =
    if (positions.first() == positions.last()) positions
    else positions.plusElement(positions.first())
  return update(listOf(feature.copy(geometry = Polygon(polygon.coordinates + listOf(ring))))) !=
    null
}

fun FeatureEditorState.addPart(id: FeatureId, part: Polygon): Boolean {
  val feature = feature(id) ?: return false
  val parts =
    when (val geometry = feature.geometry) {
      is Polygon -> listOf(geometry.coordinates)
      is MultiPolygon -> geometry.coordinates
      else -> return false
    }
  return update(listOf(feature.copy(geometry = MultiPolygon(parts + listOf(part.coordinates))))) !=
    null
}

// #endregion hole

// #region context-menu
@Composable
fun EditorWithContextMenu(editor: FeatureEditorState, showMenu: (Position, EditorHit?) -> Unit) {
  val map = rememberMapState { FeatureEditorLayers(editor) }
  MaplibreMap(
    state = map,
    surfaceModifier = Modifier.featureEditor(editor, map),
    interactions =
      MapInteractions {
        callbacks {
          longClick {
            onEvent { event -> // (1)!
              val position = event.position ?: return@onEvent ClickResult.Pass
              val hit =
                editor
                  .hitTest(event.screenOffset, 12.dp, map::positionFromScreenLocation)
                  .firstOrNull()
              showMenu(position, hit)
              ClickResult.Consume
            }
          }
        }
      },
  )
}

// #endregion context-menu

// #region focus
@Composable
fun EditorWithToolbar(editor: FeatureEditorState, select: SelectTool) {
  val mapFocus = remember { FocusRequester() }
  val draw = remember(select) { DrawTool(DrawShape.Polygon, nextTool = select) }
  val map = rememberMapState { FeatureEditorLayers(editor) }
  fun useTool(tool: EditorTool) {
    if (editor.tool !== tool) {
      editor.cancelDraft() // (1)!
      editor.tool = tool
    }
    mapFocus.requestFocus() // (2)!
  }
  Column {
    MaplibreMap(
      modifier = Modifier.weight(1f),
      state = map,
      surfaceModifier = Modifier.focusRequester(mapFocus).featureEditor(editor, map),
      uiOptions = MapUiOptions { bindings { keys { enabled = false } } }, // (3)!
    )
    Row {
      FilterChip(
        selected = editor.tool === select, // (4)!
        onClick = { useTool(select) },
        label = { Text("Select") },
      )
      FilterChip(
        selected = editor.tool === draw,
        onClick = { useTool(draw) },
        label = { Text("Draw") },
      )
      Button(
        enabled = editor.canUndo,
        onClick = {
          editor.undo()
          mapFocus.requestFocus()
        },
      ) {
        Text("Undo")
      }
    }
  }
}

// #endregion focus

// #region app-layers
@Composable
fun EditorWithOwnLayers(editor: FeatureEditorState) {
  val map = rememberMapState {
    val features by
      remember(editor) {
        derivedStateOf { GeoJsonData.Features(FeatureCollection(editor.features)) }
      }
    val source = rememberGeoJsonSource(features, GeoJsonOptions(synchronousUpdate = true)) // (1)!
    Anchor.Below({ it.type == "symbol" }) {
      FillLayer(id = "zones", source = source, color = const(MaterialTheme.colorScheme.tertiary))
      LineLayer(id = "zones-outline", source = source, width = const(2.dp))
    }
    Anchor.Top {
      EditorDraftLayers(editor) // (2)!
      EditorHandleLayers(editor, handleRadius = 8.dp)
    }
  }
  MaplibreMap(state = map, surfaceModifier = Modifier.featureEditor(editor, map)) {
    include(MapOverlay.Default)
    editor.selection.singleOrNull()?.let(editor::feature)?.let { zone ->
      Text(
        "${zone.geometry.area().inHectares.toInt()} ha",
        Modifier.placedAt(
          zone.geometry.computeBbox().center().coordinates,
          alignment = Alignment.Center,
        ),
      )
    }
  }
}

// #endregion app-layers
