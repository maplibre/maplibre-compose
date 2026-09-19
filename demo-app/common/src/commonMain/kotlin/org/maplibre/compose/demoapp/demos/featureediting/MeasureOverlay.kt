package org.maplibre.compose.demoapp.demos.featureediting

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.sqrt
import kotlinx.coroutines.delay
import org.maplibre.compose.editing.EditorFeature
import org.maplibre.compose.editing.HandleKind
import org.maplibre.compose.map.LocalMapState
import org.maplibre.compose.overlay.MapOverlayScope
import org.maplibre.spatialk.geojson.LineString
import org.maplibre.spatialk.geojson.Polygon
import org.maplibre.spatialk.geojson.Position
import org.maplibre.spatialk.turf.measurement.area
import org.maplibre.spatialk.turf.measurement.bearingTo
import org.maplibre.spatialk.turf.measurement.distance
import org.maplibre.spatialk.units.extensions.meters

/** Digits of equal width, so live numbers do not jitter. */
internal val TextStyle.tabular: TextStyle
  get() = copy(fontFeatureSettings = "tnum")

/** The number of shapes that get a label each. Beyond it only selected shapes do. */
private const val LABEL_LIMIT = 20

/**
 * Animates a measurement: it snaps while [live] and eases over 350 ms otherwise, so undo, presets
 * and unit switches count up or down.
 */
@Composable
internal fun animatedMeasure(value: Double, live: Boolean): Double {
  val animatable = remember { Animatable(value.toFloat()) }
  LaunchedEffect(value, live) {
    if (live) animatable.snapTo(value.toFloat())
    else animatable.animateTo(value.toFloat(), tween(350, easing = FastOutSlowInEasing))
  }
  return animatable.value.toDouble()
}

/** A name, number and caption for every shape, following its label anchor. */
@Composable
internal fun MapOverlayScope.ShapeLabels(state: FeatureEditingState) {
  val editor = state.editor
  val features = editor.features
  val labeled =
    if (features.size <= LABEL_LIMIT) features else features.filter { it.id in editor.selection }
  val entries = rememberLaggingEntries(labeled)
  val live = editor.gestureInProgress || state.simplifyScrub != null
  for (entry in entries) {
    key(entry.id) {
      // The entry copy lags a frame; the live feature keeps the label on a dragged shape.
      val feature = editor.feature(entry.id) ?: entry.feature
      val selected = entry.id in editor.selection
      val measure = remember(feature.geometry) { ShapeMeasure.of(feature) }
      val line = feature.geometry as? LineString
      val fraction = if (selected) state.stationFraction else 0.5
      val station = remember(line, fraction) { line?.let { stationOf(it, fraction) } }
      val anchor = station?.position ?: measure.labelAnchor
      AnimatedVisibility(
        visibleState = entry.visible,
        modifier =
          if (line != null) {
            Modifier.placedAt(anchor, Alignment.BottomCenter).padding(bottom = 14.dp)
          } else Modifier.placedAt(anchor, Alignment.Center),
        enter =
          fadeIn(tween(120)) +
            scaleIn(
              initialScale = 0.85f,
              animationSpec = spring(dampingRatio = 0.6f, stiffness = 500f),
            ),
        exit = fadeOut(tween(120)),
      ) {
        ShapeLabel(state, feature, measure, station, selected, live)
      }
    }
  }
}

@Composable
private fun ShapeLabel(
  state: FeatureEditingState,
  feature: EditorFeature,
  measure: ShapeMeasure,
  station: Station?,
  selected: Boolean,
  live: Boolean,
) {
  val colors = MaterialTheme.colorScheme
  val primary = animatedMeasure(measure.primaryMeters, live)
  Surface(
    color =
      if (selected) colors.surfaceContainerHighest.copy(alpha = 0.92f)
      else colors.surfaceContainer.copy(alpha = 0.8f),
    contentColor = colors.onSurface,
    shape = RoundedCornerShape(12.dp),
    shadowElevation = if (selected) 2.dp else 0.dp,
  ) {
    AnimatedContent(
      targetState = state.units,
      transitionSpec = {
        (slideInVertically(tween(150)) { it / 2 } + fadeIn(tween(150))) togetherWith
          (slideOutVertically(tween(150)) { -it / 2 } + fadeOut(tween(150)))
      },
      label = "units",
    ) { units ->
      Column(
        Modifier.padding(horizontal = 10.dp, vertical = 6.dp).widthIn(max = 200.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        Text(
          feature.displayName,
          style = MaterialTheme.typography.labelSmall,
          color = colors.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        Text(
          units.primary(measure.kind, primary),
          style =
            if (selected) MaterialTheme.typography.titleMedium.tabular
            else MaterialTheme.typography.labelLarge.tabular,
          color = if (selected) colors.primary else colors.onSurfaceVariant,
        )
        if (selected) {
          val caption =
            when (measure.kind) {
              ShapeKind.Polygon -> "${units.length(measure.length)} perimeter"
              ShapeKind.Circle -> "r ${units.length(measure.radius ?: 0.meters)}"
              ShapeKind.Line ->
                if (station != null) {
                  "${units.length(station.along)} from start · ${units.bearing(station.bearing)}"
                } else units.length(measure.length)
            }
          Text(
            caption,
            style = MaterialTheme.typography.labelSmall.tabular,
            color = colors.onSurfaceVariant,
          )
        }
      }
    }
  }
}

/** The length of each edge of the selected polygon or line, rotated along the edge. */
@Composable
internal fun MapOverlayScope.EdgeLabels(state: FeatureEditingState) {
  val editor = state.editor
  val feature = state.selected.singleOrNull() ?: return
  if (feature.isCircle) return
  if (state.frameGesture != null || state.simplifyScrub != null) return
  val measure = remember(feature.geometry) { ShapeMeasure.of(feature) }
  if (measure.edges.size > EDGE_LABEL_LIMIT) return
  val active = editor.activeHandle
  val kept: Set<Int>? =
    if (!editor.gestureInProgress) null
    else {
      val ref = active?.vertex?.takeIf { active.kind == HandleKind.Vertex } ?: return
      touchingEdges(feature, ref.path)
    }
  val mapState = checkNotNull(LocalMapState.current)
  val camera = mapState.cameraPosition
  val placed =
    remember(measure, camera, kept) {
      measure.edges.mapIndexedNotNull { index, edge ->
        if (kept != null && index !in kept) return@mapIndexedNotNull null
        val a = mapState.screenLocationFromPosition(edge.a) ?: return@mapIndexedNotNull null
        val b = mapState.screenLocationFromPosition(edge.b) ?: return@mapIndexedNotNull null
        val dx = (b.x - a.x).value
        val dy = (b.y - a.y).value
        if (sqrt(dx * dx + dy * dy) < MIN_EDGE_LABEL_LENGTH) return@mapIndexedNotNull null
        var angle = atan2(dy, dx) * 180f / PI.toFloat()
        if (angle > 90f) angle -= 180f
        if (angle <= -90f) angle += 180f
        Triple(index, edge, angle)
      }
    }
  val colors = MaterialTheme.colorScheme
  for ((index, edge, angle) in placed) {
    key(index) {
      Text(
        state.units.length(edge.length),
        modifier =
          Modifier.placedAt(edge.midpoint, Alignment.Center)
            .rotate(angle)
            .background(colors.surfaceContainer.copy(alpha = 0.85f), RoundedCornerShape(4.dp))
            .padding(horizontal = 4.dp, vertical = 1.dp),
        style = MaterialTheme.typography.labelSmall.tabular.copy(fontSize = 11.sp),
        color = colors.onSurfaceVariant,
        maxLines = 1,
      )
    }
  }
}

private const val EDGE_LABEL_LIMIT = 24
private const val MIN_EDGE_LABEL_LENGTH = 56f

/** Indices into [ShapeMeasure.edges] of the segments that end or start at the vertex at [path]. */
private fun touchingEdges(feature: EditorFeature, path: List<Int>): Set<Int> =
  when (val geometry = feature.geometry) {
    is Polygon -> {
      val ring = path.getOrNull(0) ?: return emptySet()
      val index = path.getOrNull(1) ?: return emptySet()
      val offset = geometry.coordinates.take(ring).sumOf { it.size - 1 }
      val count = geometry.coordinates.getOrNull(ring)?.let { it.size - 1 } ?: return emptySet()
      if (count <= 0) emptySet()
      else setOf(offset + (index + count - 1) % count, offset + index % count)
    }
    is LineString -> {
      val index = path.singleOrNull() ?: return emptySet()
      buildSet {
        if (index > 0) add(index - 1)
        if (index < geometry.coordinates.size - 1) add(index)
      }
    }
    else -> emptySet()
  }

/** The measurement of the shape being drawn, beside the cursor or over the last placed point. */
@Composable
internal fun MapOverlayScope.DraftLabel(state: FeatureEditingState) {
  val editor = state.editor
  val draft = editor.draft ?: return
  val tool = editor.tool
  val mouse = state.lastPointerType == PointerType.Mouse
  val positions = draft.positions
  val cursor = draft.cursor
  val units = state.units
  val (primary, secondary) =
    when {
      tool === state.circleTool -> {
        val center = positions.firstOrNull() ?: return
        val edge = cursor ?: return
        val radius = distance(center, edge)
        "r ${units.length(radius)}" to units.area((radius * radius) * PI)
      }
      tool === state.polygonTool -> {
        val outline = if (mouse && cursor != null) positions.plusElement(cursor) else positions
        val area =
          if (outline.distinct().size >= 3) {
            units.area(Polygon(listOf(outline.plusElement(outline.first()))).area())
          } else null
        area to segmentText(units, positions, cursor, mouse)
      }
      tool === state.lineTool -> {
        val path = if (mouse && cursor != null) positions.plusElement(cursor) else positions
        val length =
          path.zipWithNext { a, b -> distance(a, b) }.fold(0.meters) { acc, l -> acc + l }
        (if (path.size >= 2) units.length(length) else null) to
          segmentText(units, positions, cursor, mouse)
      }
      else -> return
    }
  if (primary == null && secondary == null) return
  val anchor = if (mouse) cursor ?: return else positions.lastOrNull() ?: return
  val colors = MaterialTheme.colorScheme
  Surface(
    color = colors.surfaceContainerHighest.copy(alpha = 0.92f),
    contentColor = colors.onSurface,
    shape = RoundedCornerShape(12.dp),
    modifier =
      if (mouse) Modifier.placedAt(anchor, Alignment.TopStart).padding(start = 14.dp, top = 14.dp)
      else Modifier.placedAt(anchor, Alignment.BottomCenter).padding(bottom = 14.dp),
  ) {
    Column(
      Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      if (primary != null) {
        Text(
          primary,
          style = MaterialTheme.typography.labelLarge.tabular,
          color = colors.primary,
        )
      }
      if (secondary != null) {
        Text(
          secondary,
          style = MaterialTheme.typography.labelSmall.tabular,
          color = colors.onSurfaceVariant,
        )
      }
    }
  }
}

/** "142 m · 87°" for the rubber-band segment on mouse, or the last placed segment on touch. */
private fun segmentText(
  units: MeasureUnits,
  positions: List<Position>,
  cursor: Position?,
  mouse: Boolean,
): String? {
  val (a, b) =
    if (mouse) {
      val last = positions.lastOrNull() ?: return null
      last to (cursor ?: return null)
    } else {
      if (positions.size < 2) return null
      positions[positions.size - 2] to positions.last()
    }
  return "${units.length(distance(a, b))} · ${units.bearing(a.bearingTo(b))}"
}

/** The rotation, scale factor, offset or radius of the frame gesture in progress. */
@Composable
internal fun MapOverlayScope.TransformReadout(state: FeatureEditingState) {
  val gesture = state.frameGesture
  var shown by remember { mutableStateOf(gesture) }
  if (gesture != null && gesture.readout.isNotEmpty()) shown = gesture
  val current = shown ?: return
  val colors = MaterialTheme.colorScheme
  AnimatedVisibility(
    visible = gesture != null && gesture.readout.isNotEmpty(),
    modifier =
      Modifier.placedAt(current.pointer.position, Alignment.BottomStart)
        .padding(start = 16.dp, bottom = 16.dp),
    enter = fadeIn(tween(80)),
    exit = fadeOut(tween(120)),
  ) {
    Surface(
      color = colors.inverseSurface,
      contentColor = colors.inverseOnSurface,
      shape = RoundedCornerShape(8.dp),
    ) {
      Text(
        current.readout,
        Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        style = MaterialTheme.typography.labelLarge.tabular,
        maxLines = 1,
      )
    }
  }
}

/** The validation message at the vertex whose drag frame was rejected. */
@Composable
internal fun MapOverlayScope.ValidationTooltip(state: FeatureEditingState) {
  val editor = state.editor
  val error = editor.validationError
  val handle = editor.activeHandle
  var message by remember { mutableStateOf<String?>(null) }
  var visible by remember { mutableStateOf(false) }
  var position by remember { mutableStateOf<Position?>(null) }
  LaunchedEffect(error, handle?.vertex) {
    if (error != null && handle != null) {
      message = error
      visible = true
    } else if (visible) {
      delay(ERROR_LINGER_MILLIS)
      visible = false
    }
  }
  val anchor = handle?.position?.also { position = it } ?: position ?: return
  val text = message ?: return
  val colors = MaterialTheme.colorScheme
  AnimatedVisibility(
    visible = visible,
    modifier = Modifier.placedAt(anchor, Alignment.BottomCenter).padding(bottom = 18.dp),
    enter = fadeIn(tween(100)),
    exit = fadeOut(tween(200)),
  ) {
    Surface(
      color = colors.errorContainer,
      contentColor = colors.onErrorContainer,
      shape = RoundedCornerShape(8.dp),
    ) {
      Text(
        text,
        Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        style = MaterialTheme.typography.labelMedium,
        maxLines = 1,
      )
    }
  }
}

/** How long an error stays on screen after the editor accepts the next change. */
internal const val ERROR_LINGER_MILLIS = 1_200L
