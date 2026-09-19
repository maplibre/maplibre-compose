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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.sqrt
import kotlinx.coroutines.delay
import org.maplibre.compose.editing.EditorFeature
import org.maplibre.compose.editing.HandleKind
import org.maplibre.compose.map.LocalMapState
import org.maplibre.compose.map.MapState
import org.maplibre.compose.overlay.MapOverlayScope
import org.maplibre.spatialk.geojson.FeatureId
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

/** Where a line label sits above its anchor. */
private val LINE_LABEL_GAP = 14.dp

/** How far an edge label sits from its edge, clear of the midpoint handle. */
private val EDGE_LABEL_OFFSET = 12.dp

/** The shapes that get a label, one lagging entry each, with the measured size of every label. */
internal class ShapeLabelEntries(val entries: List<LaggingEntry>) {
  val sizes = mutableStateMapOf<FeatureId, DpSize>()
}

@Composable
internal fun rememberShapeLabelEntries(state: FeatureEditingState): ShapeLabelEntries {
  val editor = state.editor
  val features = editor.features
  val labeled =
    if (features.size <= LABEL_LIMIT) features else features.filter { it.id in editor.selection }
  val entries = rememberLaggingEntries(labeled, editor.selection)
  return remember { ShapeLabelEntries(entries) }
}

/** A shape label's anchor and, for a line, its station. Remembered, so labels can skip. */
private class LabelPlacement(
  val feature: EditorFeature,
  val measure: ShapeMeasure,
  val station: Station?,
  val selected: Boolean,
) {
  val anchor: Position
    get() = station?.position ?: measure.labelAnchor

  val line: Boolean
    get() = station != null
}

@Composable
private fun rememberLabelPlacement(
  state: FeatureEditingState,
  entry: LaggingEntry,
): LabelPlacement {
  // The entry copy lags a frame; the live feature keeps the label on a dragged shape.
  val feature = state.editor.feature(entry.id) ?: entry.feature
  val selected = entry.selected
  val measure = remember(feature.geometry) { ShapeMeasure.of(feature) }
  val line = feature.geometry as? LineString
  val fraction = if (selected) state.stationFraction else 0.5
  val station = remember(line, fraction) { line?.let { stationOf(it, fraction) } }
  return remember(feature, measure, station, selected) {
    LabelPlacement(feature, measure, station, selected)
  }
}

/** The rect a label of [size] covers in map dp, or null while its anchor is off the map. */
private fun labelRect(
  mapState: MapState,
  anchor: Position,
  line: Boolean,
  size: DpSize,
): DpRect? {
  val screen = mapState.screenLocationFromPosition(anchor) ?: return null
  val left = screen.x - size.width / 2
  val top = if (line) screen.y - LINE_LABEL_GAP - size.height else screen.y - size.height / 2
  return DpRect(left, top, left + size.width, top + size.height)
}

private fun DpRect.overlaps(other: DpRect, margin: Dp): Boolean =
  left < other.right + margin &&
    other.left < right + margin &&
    top < other.bottom + margin &&
    other.top < bottom + margin

private fun DpRect.contains(point: DpOffset, margin: Dp): Boolean =
  point.x > left - margin &&
    point.x < right + margin &&
    point.y > top - margin &&
    point.y < bottom + margin

/**
 * A name, number and caption for every shape, following its label anchor. The selected label draws
 * over the others, which hide while they overlap it.
 */
@Composable
internal fun MapOverlayScope.ShapeLabels(state: FeatureEditingState, labels: ShapeLabelEntries) {
  val editor = state.editor
  val live = editor.gestureInProgress || state.simplifyScrub != null
  val mapState = checkNotNull(LocalMapState.current)
  val density = LocalDensity.current
  val placements =
    labels.entries.map { entry -> key(entry.id) { entry to rememberLabelPlacement(state, entry) } }
  val camera = mapState.cameraPosition
  val selected = placements.singleOrNull { it.second.selected }?.second
  val selectedSize = selected?.let { labels.sizes[it.feature.id] }
  val selectedRect =
    remember(camera, selected, selectedSize) {
      if (selected == null || selectedSize == null) null
      else labelRect(mapState, selected.anchor, selected.line, selectedSize)
    }
  for ((entry, placement) in placements) {
    key(entry.id) {
      val id = entry.id
      DisposableEffect(id) { onDispose { labels.sizes.remove(id) } }
      val size = labels.sizes[id]
      val hidden =
        remember(camera, placement, selectedRect, size) {
          !placement.selected &&
            selectedRect != null &&
            size != null &&
            labelRect(mapState, placement.anchor, placement.line, size)
              ?.overlaps(selectedRect, LABEL_CLEARANCE) == true
        }
      AnimatedVisibility(
        visibleState = entry.visible,
        modifier =
          Modifier.zIndex(if (placement.selected) 1f else 0f)
            .then(
              if (placement.line) {
                Modifier.placedAt(placement.anchor, Alignment.BottomCenter)
                  .padding(bottom = LINE_LABEL_GAP)
              } else Modifier.placedAt(placement.anchor, Alignment.Center)
            ),
        enter =
          fadeIn(tween(120)) +
            scaleIn(
              initialScale = 0.85f,
              animationSpec = spring(dampingRatio = 0.6f, stiffness = 500f),
            ),
        exit = fadeOut(tween(120)),
      ) {
        AnimatedVisibility(
          visible = !hidden,
          enter = fadeIn(tween(120)),
          exit = fadeOut(tween(120)),
        ) {
          ShapeLabel(
            state,
            placement,
            live,
            Modifier.onSizeChanged {
              labels.sizes[id] = with(density) { DpSize(it.width.toDp(), it.height.toDp()) }
            },
          )
        }
      }
    }
  }
}

/** The gap a label keeps from the selected label before it hides or is skipped. */
private val LABEL_CLEARANCE = 8.dp

// The label chrome is a Box rather than a Surface: a Surface blocks pointer input, and presses on
// a label must reach the handles and the map under it.
@Composable
private fun ShapeLabel(
  state: FeatureEditingState,
  placement: LabelPlacement,
  live: Boolean,
  modifier: Modifier = Modifier,
) {
  val feature = placement.feature
  val measure = placement.measure
  val station = placement.station
  val selected = placement.selected
  val colors = MaterialTheme.colorScheme
  val primary = animatedMeasure(measure.primaryMeters, live)
  val shape = RoundedCornerShape(12.dp)
  Box(
    modifier
      .shadow(if (selected) 2.dp else 0.dp, shape)
      .background(
        if (selected) colors.surfaceContainerHighest.copy(alpha = 0.92f)
        else colors.surfaceContainer.copy(alpha = 0.8f),
        shape,
      )
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

/** One edge label: its edge, the offset that clears the midpoint handle, and its rotation. */
private class PlacedEdge(val index: Int, val edge: Edge, val offset: DpOffset, val angle: Float)

/**
 * The length of each edge of the selected polygon or line, rotated along the edge and offset to its
 * outer side. An edge label that would sit on the shape label is skipped.
 */
@Composable
internal fun MapOverlayScope.EdgeLabels(state: FeatureEditingState, labels: ShapeLabelEntries) {
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
  // The shape label's entry lags a frame; the edge labels wait for it so they do not appear first.
  val entry = labels.entries.firstOrNull { it.id == feature.id }
  val shown = entry != null && entry.visible.targetState
  val line = feature.geometry as? LineString
  val fraction = state.stationFraction
  val labelAnchor = remember(line, fraction) { line?.let { stationPoint(it, fraction) } }
  val mapState = checkNotNull(LocalMapState.current)
  val camera = mapState.cameraPosition
  val labelSize = labels.sizes[feature.id]
  val placed =
    remember(measure, camera, kept, labelAnchor, labelSize) {
      val labelRect = labelSize?.let {
        labelRect(mapState, labelAnchor ?: measure.labelAnchor, line != null, it)
      }
      val center = mapState.screenLocationFromPosition(measure.labelAnchor)
      measure.edges.mapIndexedNotNull { index, edge ->
        if (kept != null && index !in kept) return@mapIndexedNotNull null
        val a = mapState.screenLocationFromPosition(edge.a) ?: return@mapIndexedNotNull null
        val b = mapState.screenLocationFromPosition(edge.b) ?: return@mapIndexedNotNull null
        val dx = (b.x - a.x).value
        val dy = (b.y - a.y).value
        val length = sqrt(dx * dx + dy * dy)
        if (length < MIN_EDGE_LABEL_LENGTH) return@mapIndexedNotNull null
        var angle = atan2(dy, dx) * 180f / PI.toFloat()
        if (angle > 90f) angle -= 180f
        if (angle <= -90f) angle += 180f
        // The unit normal, pointing away from a polygon's label anchor or below a line.
        var nx = -dy / length
        var ny = dx / length
        val mid = DpOffset((a.x + b.x) / 2, (a.y + b.y) / 2)
        val inward =
          if (line == null && center != null) {
            nx * (center.x - mid.x).value + ny * (center.y - mid.y).value > 0
          } else ny < 0
        if (inward) {
          nx = -nx
          ny = -ny
        }
        val offset = DpOffset(EDGE_LABEL_OFFSET * nx, EDGE_LABEL_OFFSET * ny)
        if (labelRect?.contains(mid + offset, LABEL_CLEARANCE) == true) {
          return@mapIndexedNotNull null
        }
        PlacedEdge(index, edge, offset, angle)
      }
    }
  val colors = MaterialTheme.colorScheme
  for (item in placed) {
    key(item.index) {
      AnimatedVisibility(
        visible = shown,
        modifier =
          Modifier.placedAt(item.edge.midpoint, Alignment.Center)
            .offset(item.offset.x, item.offset.y)
            .rotate(item.angle),
        enter = fadeIn(tween(120)),
        exit = fadeOut(tween(120)),
      ) {
        Text(
          state.units.length(item.edge.length),
          modifier =
            Modifier.background(
                colors.surfaceContainer.copy(alpha = 0.85f),
                RoundedCornerShape(4.dp),
              )
              .padding(horizontal = 4.dp, vertical = 1.dp),
          style = MaterialTheme.typography.labelSmall.tabular.copy(fontSize = 11.sp),
          color = colors.onSurfaceVariant,
          maxLines = 1,
        )
      }
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
  Box(
    (if (mouse) Modifier.placedAt(anchor, Alignment.TopStart).padding(start = 14.dp, top = 14.dp)
      else Modifier.placedAt(anchor, Alignment.BottomCenter).padding(bottom = 14.dp))
      .background(colors.surfaceContainerHighest.copy(alpha = 0.92f), RoundedCornerShape(12.dp))
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

/**
 * The rotation, scale factor, offset or radius of the frame gesture in progress, beside the pointer
 * on the side with more room.
 */
@Composable
internal fun MapOverlayScope.TransformReadout(state: FeatureEditingState) {
  val gesture = state.frameGesture?.takeIf { it.readout.isNotEmpty() }
  // The last readout stays for the exit animation. Written after composition, so the scope is not
  // invalidated on every drag frame.
  var shown by remember { mutableStateOf(gesture) }
  if (gesture != null) SideEffect { shown = gesture }
  val current = gesture ?: shown ?: return
  val size = LocalMapState.current?.viewport?.size
  val right = size != null && current.pointer.screen.x > size.width / 2
  val top = size != null && current.pointer.screen.y < size.height / 2
  val alignment =
    when {
      right && top -> Alignment.TopEnd
      right -> Alignment.BottomEnd
      top -> Alignment.TopStart
      else -> Alignment.BottomStart
    }
  val colors = MaterialTheme.colorScheme
  AnimatedVisibility(
    visible = gesture != null,
    modifier =
      Modifier.zIndex(2f)
        .placedAt(current.pointer.position, alignment)
        .padding(
          start = if (right) 0.dp else 16.dp,
          end = if (right) 16.dp else 0.dp,
          top = if (top) 16.dp else 0.dp,
          bottom = if (top) 0.dp else 16.dp,
        ),
    enter = fadeIn(tween(80)),
    exit = fadeOut(tween(120)),
  ) {
    Text(
      current.readout,
      Modifier.background(colors.inverseSurface, RoundedCornerShape(8.dp))
        .padding(horizontal = 10.dp, vertical = 6.dp),
      style = MaterialTheme.typography.labelLarge.tabular,
      color = colors.inverseOnSurface,
      maxLines = 1,
    )
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
  // The last handle position keeps the tooltip in place while it lingers after the handle clears.
  var position by remember { mutableStateOf<Position?>(null) }
  if (handle != null) SideEffect { position = handle.position }
  LaunchedEffect(error, handle?.vertex) {
    if (error != null && handle != null) {
      message = error
      visible = true
    } else if (visible) {
      delay(ERROR_LINGER_MILLIS)
      visible = false
    }
  }
  val anchor = handle?.position ?: position ?: return
  val text = message ?: return
  val colors = MaterialTheme.colorScheme
  // A vertex near a side edge gets the tooltip on its inner side, so the text is not clipped.
  val mapState = LocalMapState.current
  val camera = mapState?.cameraPosition
  val size = mapState?.viewport?.size
  val alignment =
    remember(camera, size, anchor) {
      val screen = mapState?.screenLocationFromPosition(anchor)
      when {
        size == null || screen == null -> Alignment.BottomCenter
        screen.x < EDGE_TOOLTIP_INSET -> Alignment.BottomStart
        screen.x > size.width - EDGE_TOOLTIP_INSET -> Alignment.BottomEnd
        else -> Alignment.BottomCenter
      }
    }
  AnimatedVisibility(
    visible = visible,
    // Above the selected shape label, which draws over the other overlay children.
    modifier = Modifier.zIndex(2f).placedAt(anchor, alignment).padding(bottom = 18.dp),
    enter = fadeIn(tween(100)),
    exit = fadeOut(tween(200)),
  ) {
    Text(
      text,
      Modifier.background(colors.errorContainer, RoundedCornerShape(8.dp))
        .padding(horizontal = 10.dp, vertical = 6.dp),
      style = MaterialTheme.typography.labelMedium,
      color = colors.onErrorContainer,
      maxLines = 1,
    )
  }
}

/** How close to a side of the map a vertex must be for its tooltip to move to its inner side. */
private val EDGE_TOOLTIP_INSET = 100.dp

/** How long an error stays on screen after the editor accepts the next change. */
internal const val ERROR_LINGER_MILLIS = 1_200L
