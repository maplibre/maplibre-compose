package org.maplibre.compose.editing

import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.time.Duration
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.maplibre.compose.expressions.ast.Expression
import org.maplibre.compose.expressions.dsl.any
import org.maplibre.compose.expressions.dsl.condition
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.eq
import org.maplibre.compose.expressions.dsl.feature
import org.maplibre.compose.expressions.dsl.switch
import org.maplibre.compose.expressions.value.BooleanValue
import org.maplibre.compose.expressions.value.CirclePitchScale
import org.maplibre.compose.expressions.value.GeometryType
import org.maplibre.compose.expressions.value.LineCap
import org.maplibre.compose.expressions.value.LineJoin
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.layers.FillLayer
import org.maplibre.compose.layers.LineLayer
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.GeoJsonOptions
import org.maplibre.compose.sources.GeoJsonSource
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.style.TransitionOptions
import org.maplibre.compose.util.MaplibreComposable
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.FeatureId
import org.maplibre.spatialk.geojson.Geometry
import org.maplibre.spatialk.geojson.LineString
import org.maplibre.spatialk.geojson.Point
import org.maplibre.spatialk.geojson.Polygon
import org.maplibre.spatialk.geojson.Position

/**
 * Draws [state] as fill, line and circle layers.
 *
 * Composes [EditorFeatureLayers], [EditorDraftLayers] and [EditorHandleLayers] in that order at the
 * composition's current anchor. The three may be called separately under different anchors, for
 * example features under labels and handles on top. Layer ids start with [idPrefix]; a second
 * editor on the same map needs a different prefix, since the style composition rejects duplicate
 * layer ids.
 */
@Composable
@MaplibreComposable
public fun FeatureEditorLayers(
  state: FeatureEditorState,
  colors: EditorColors = EditorColors(),
  idPrefix: String = "feature-editor",
  lineWidth: Dp = 2.dp,
  handleRadius: Dp = 6.dp,
) {
  EditorFeatureLayers(state, colors, idPrefix, lineWidth)
  EditorDraftLayers(state, colors, idPrefix, lineWidth)
  EditorHandleLayers(state, colors, idPrefix, handleRadius)
}

/**
 * Draws unselected and selected features: fill and outline for polygons, line for lines, circle for
 * points.
 *
 * The unselected source is re-sent when [FeatureEditorState.features] or the selection changes.
 * Apps with thousands of features draw their own layers and load only the features being edited
 * into the state.
 */
@Composable
@MaplibreComposable
public fun EditorFeatureLayers(
  state: FeatureEditorState,
  colors: EditorColors = EditorColors(),
  idPrefix: String = "feature-editor",
  lineWidth: Dp = 2.dp,
) {
  val unselected by
    remember(state) {
      derivedStateOf { featuresData(unselectedFeatures(state.features, state.selection)) }
    }
  val selected by
    remember(state) {
      derivedStateOf { featuresData(selectedFeatures(state.features, state.selection)) }
    }
  FeatureLayers(
    source = rememberGeoJsonSource(unselected, synchronousOptions),
    idPrefix = idPrefix,
    fill = colors.fill,
    stroke = colors.stroke,
    handleFill = colors.handleFill,
    lineWidth = lineWidth,
  )
  FeatureLayers(
    source = rememberGeoJsonSource(selected, synchronousOptions),
    idPrefix = "$idPrefix-selected",
    fill = colors.selectedFill,
    stroke = colors.selectedStroke,
    handleFill = colors.handleFill,
    lineWidth = lineWidth,
  )
}

/**
 * Draws the draft preview: fill and line including the cursor segment. Draft vertices are handles
 * and drawn by [EditorHandleLayers].
 */
@Composable
@MaplibreComposable
public fun EditorDraftLayers(
  state: FeatureEditorState,
  colors: EditorColors = EditorColors(),
  idPrefix: String = "feature-editor",
  lineWidth: Dp = 2.dp,
) {
  val draft by
    remember(state) {
      derivedStateOf {
        featuresData(
          listOfNotNull(state.draft?.let(::draftPreviewGeometry)?.let { Feature(it, null) })
        )
      }
    }
  val source = rememberGeoJsonSource(draft, synchronousOptions)
  FillLayer(
    id = "$idPrefix-draft-fill",
    source = source,
    filter = polygonFilter,
    color = const(colors.draft.copy(alpha = colors.draft.alpha * FILL_ALPHA)),
    outlineColor = const(colors.draft),
  )
  LineLayer(
    id = "$idPrefix-draft-line",
    source = source,
    color = const(colors.draft),
    width = const(lineWidth),
    cap = const(LineCap.Round),
    join = const(LineJoin.Round),
  )
}

/**
 * Draws [FeatureEditorState.handles] as circles of [handleRadius]. Unknown [HandleKind]s draw as
 * vertices.
 */
@Composable
@MaplibreComposable
public fun EditorHandleLayers(
  state: FeatureEditorState,
  colors: EditorColors = EditorColors(),
  idPrefix: String = "feature-editor",
  handleRadius: Dp = 6.dp,
) {
  val handles by remember(state) { derivedStateOf { featuresData(handleFeatures(state.handles)) } }
  val activeIndex by
    remember(state) {
      derivedStateOf { state.activeHandle?.let { state.handles.indexOf(it) } ?: -1 }
    }
  val hoverIndex by
    remember(state) {
      derivedStateOf { (state.hover as? HandleHit)?.let { state.handles.indexOf(it.handle) } ?: -1 }
    }
  val index = feature[INDEX]
  val highlighted = any(index eq const(activeIndex), index eq const(hoverIndex))
  val midpoint = feature[KIND] eq const(KIND_MIDPOINT)
  val draft = feature[DRAFT] eq const(true)
  CircleLayer(
    id = "$idPrefix-handle",
    source = rememberGeoJsonSource(handles, synchronousOptions),
    radius =
      switch(
        condition(highlighted, const(handleRadius + HIGHLIGHT_GROWTH)),
        condition(midpoint, const(handleRadius * 2 / 3)),
        fallback = const(handleRadius),
      ),
    radiusTransition = instant,
    color =
      switch(
        condition(highlighted, const(colors.activeHandleFill)),
        condition(midpoint, const(colors.midpointFill)),
        fallback = const(colors.handleFill),
      ),
    colorTransition = instant,
    strokeColor =
      switch(condition(draft, const(colors.draft)), fallback = const(colors.handleStroke)),
    strokeColorTransition = instant,
    strokeWidth = const(HANDLE_STROKE_WIDTH),
    pitchScale = const(CirclePitchScale.Viewport),
  )
}

@Composable
@MaplibreComposable
private fun FeatureLayers(
  source: GeoJsonSource,
  idPrefix: String,
  fill: Color,
  stroke: Color,
  handleFill: Color,
  lineWidth: Dp,
) {
  FillLayer(
    id = "$idPrefix-fill",
    source = source,
    filter = polygonFilter,
    color = const(fill),
    outlineColor = const(stroke),
  )
  LineLayer(
    id = "$idPrefix-line",
    source = source,
    filter = outlineFilter,
    color = const(stroke),
    width = const(lineWidth),
    cap = const(LineCap.Round),
    join = const(LineJoin.Round),
  )
  CircleLayer(
    id = "$idPrefix-point",
    source = source,
    filter = pointFilter,
    radius = const(POINT_RADIUS),
    color = const(stroke),
    strokeColor = const(handleFill),
    strokeWidth = const(HANDLE_STROKE_WIDTH),
    pitchScale = const(CirclePitchScale.Viewport),
  )
}

/** Features of [features] whose id is not in [selection], in order. */
internal fun unselectedFeatures(
  features: List<EditorFeature>,
  selection: Set<FeatureId>,
): List<EditorFeature> = features.filter { it.id !in selection }

/** Features of [features] whose id is in [selection], in order. */
internal fun selectedFeatures(
  features: List<EditorFeature>,
  selection: Set<FeatureId>,
): List<EditorFeature> = features.filter { it.id in selection }

/**
 * The geometry the draft layers draw for [draft], or null when there is nothing to draw: a Point
 * draft, a line with fewer than two positions including the cursor, or a rectangle without a second
 * corner.
 */
internal fun draftPreviewGeometry(draft: EditorDraft): Geometry? {
  val positions = draft.positions + listOfNotNull(draft.cursor)
  return when (draft.shape) {
    DrawShape.Point -> null
    DrawShape.LineString -> if (positions.size >= 2) LineString(positions) else null
    DrawShape.Polygon ->
      when {
        positions.size >= 3 -> Polygon(listOf(positions.plusElement(positions.first())))
        positions.size == 2 -> LineString(positions)
        else -> null
      }
    DrawShape.Rectangle -> {
      val first = draft.positions.firstOrNull() ?: return null
      val second = draft.positions.getOrNull(1) ?: draft.cursor ?: return null
      Polygon(listOf(rectangleRing(first, second)))
    }
  }
}

/** One point per handle with `index`, `kind` and `draft` properties. */
internal fun handleFeatures(handles: List<EditorHandle>): List<Feature<Point, JsonObject>> =
  handles.mapIndexed { index, handle ->
    Feature(
      Point(handle.position),
      buildJsonObject {
        put(INDEX, index)
        put(
          KIND,
          when (handle.kind) {
            HandleKind.Vertex -> KIND_VERTEX
            HandleKind.Midpoint -> KIND_MIDPOINT
            else -> KIND_OTHER
          },
        )
        put(DRAFT, handle.vertex != null && handle.vertex.featureId == null)
      },
    )
  }

private fun rectangleRing(a: Position, b: Position): List<Position> {
  val start = Position(a.longitude, a.latitude)
  return listOf(
    start,
    Position(b.longitude, a.latitude),
    Position(b.longitude, b.latitude),
    Position(a.longitude, b.latitude),
    start,
  )
}

private fun featuresData(features: List<Feature<Geometry, JsonObject?>>): GeoJsonData =
  GeoJsonData.Features(FeatureCollection(features))

private val synchronousOptions = GeoJsonOptions(synchronousUpdate = true)

// The editor state drives these properties per frame, so the map must not add a transition.
private val instant = TransitionOptions(Duration.ZERO)

private val polygonFilter: Expression<BooleanValue> =
  any(
    feature.geometryType() eq const(GeometryType.Polygon),
    feature.geometryType() eq const(GeometryType.MultiPolygon),
  )

private val outlineFilter: Expression<BooleanValue> =
  any(
    feature.geometryType() eq const(GeometryType.Polygon),
    feature.geometryType() eq const(GeometryType.MultiPolygon),
    feature.geometryType() eq const(GeometryType.LineString),
    feature.geometryType() eq const(GeometryType.MultiLineString),
  )

private val pointFilter: Expression<BooleanValue> =
  any(
    feature.geometryType() eq const(GeometryType.Point),
    feature.geometryType() eq const(GeometryType.MultiPoint),
  )

private const val INDEX = "index"
private const val KIND = "kind"
private const val DRAFT = "draft"
private const val KIND_VERTEX = "vertex"
private const val KIND_MIDPOINT = "midpoint"
private const val KIND_OTHER = "other"
private const val FILL_ALPHA = 0.15f
private val POINT_RADIUS = 6.dp
private val HANDLE_STROKE_WIDTH = 2.dp
private val HIGHLIGHT_GROWTH = 2.dp
