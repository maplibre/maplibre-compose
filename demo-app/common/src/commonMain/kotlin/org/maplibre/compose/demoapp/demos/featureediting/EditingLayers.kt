package org.maplibre.compose.demoapp.demos.featureediting

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import kotlin.time.Duration
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import org.maplibre.compose.demoapp.generated.Res
import org.maplibre.compose.demoapp.generated.open_in_full_24px
import org.maplibre.compose.demoapp.generated.open_with_24px
import org.maplibre.compose.demoapp.generated.rotate_right_24px
import org.maplibre.compose.demoapp.generated.straighten_24px
import org.maplibre.compose.editing.HandleHit
import org.maplibre.compose.expressions.dsl.case
import org.maplibre.compose.expressions.dsl.condition
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.eq
import org.maplibre.compose.expressions.dsl.feature
import org.maplibre.compose.expressions.dsl.image
import org.maplibre.compose.expressions.dsl.neq
import org.maplibre.compose.expressions.dsl.switch
import org.maplibre.compose.expressions.value.CirclePitchScale
import org.maplibre.compose.expressions.value.IconPitchAlignment
import org.maplibre.compose.expressions.value.IconRotationAlignment
import org.maplibre.compose.expressions.value.LineCap
import org.maplibre.compose.expressions.value.LineJoin
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.layers.FillLayer
import org.maplibre.compose.layers.LineLayer
import org.maplibre.compose.layers.SymbolLayer
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.GeoJsonOptions
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.style.TransitionOptions
import org.maplibre.compose.util.MaplibreComposable
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.Geometry
import org.maplibre.spatialk.geojson.LineString
import org.maplibre.spatialk.geojson.Point
import org.maplibre.spatialk.turf.measurement.bearingTo
import org.maplibre.spatialk.turf.measurement.distance
import org.maplibre.spatialk.turf.measurement.length
import org.maplibre.spatialk.turf.measurement.offset
import org.maplibre.spatialk.turf.misc.slice
import org.maplibre.spatialk.turf.transformation.circle
import org.maplibre.spatialk.units.Length

// Compose drives these properties per frame, so the map must not add its own transition.
private val instant = TransitionOptions(Duration.ZERO)
private val synchronous = GeoJsonOptions(synchronousUpdate = true)
private const val ROLE = "role"
private const val KIND = "kind"

private fun features(vararg features: Feature<Geometry, JsonObject?>?): GeoJsonData =
  GeoJsonData.Features(FeatureCollection(features.filterNotNull()))

private fun role(geometry: Geometry, role: String): Feature<Geometry, JsonObject?> =
  Feature(geometry, buildJsonObject { put(ROLE, role) })

/** The dashed frame around the selected shape, and the spokes and centre of a frame gesture. */
@Composable
@MaplibreComposable
internal fun FrameLayers(state: FeatureEditingState) {
  val colors = MaterialTheme.colorScheme
  val editor = state.editor
  val data by
    remember(state) {
      derivedStateOf {
        val id = editor.selection.singleOrNull()
        val feature = id?.let(editor::feature)
        val gesture = state.frameGesture
        if (feature == null || editor.draft != null) return@derivedStateOf features()
        val frame =
          if (gesture?.kind == FrameHandle.Rotate) null
          else
            role(frameOf(feature.geometry, FramePadding.value * state.metersPerDp).outline, "frame")
        if (gesture == null) return@derivedStateOf features(frame)
        val center = gesture.center
        val pointer = gesture.pointer.position
        val origin = gesture.origin.position
        when (gesture.kind) {
          FrameHandle.Rotate,
          FrameHandle.Scale ->
            features(
              frame,
              role(LineString(center, pointer), "spoke"),
              role(LineString(center, origin), "origin"),
              role(Point(center), "center"),
            )
          FrameHandle.Move -> {
            val moved = center.offset(distance(origin, pointer), origin.bearingTo(pointer))
            features(frame, role(LineString(center, moved), "spoke"))
          }
          FrameHandle.Radius -> features(frame, role(LineString(center, pointer), "spoke"))
          FrameHandle.Station -> features(frame)
        }
      }
    }
  val source = rememberGeoJsonSource(data, synchronous)
  LineLayer(
    id = "shape-frame",
    source = source,
    filter = feature[ROLE] eq const("frame"),
    color = const(colors.primary.copy(alpha = 0.6f)),
    width = const(1.5.dp),
    dasharray = const(listOf(3, 2)),
  )
  LineLayer(
    id = "shape-frame-origin",
    source = source,
    filter = feature[ROLE] eq const("origin"),
    color = const(colors.primary.copy(alpha = 0.4f)),
    width = const(1.5.dp),
    cap = const(LineCap.Round),
  )
  LineLayer(
    id = "shape-frame-spoke",
    source = source,
    filter = feature[ROLE] eq const("spoke"),
    color = const(colors.primary),
    width = const(1.5.dp),
    cap = const(LineCap.Round),
  )
  CircleLayer(
    id = "shape-frame-center",
    source = source,
    filter = feature[ROLE] eq const("center"),
    radius = const(5.dp),
    color = const(colors.primary),
    pitchScale = const(CirclePitchScale.Viewport),
  )
}

/** The part of the selected line between its start and the station dot. */
@Composable
@MaplibreComposable
internal fun StationLayers(state: FeatureEditingState) {
  val colors = MaterialTheme.colorScheme
  val editor = state.editor
  val data by
    remember(state) {
      derivedStateOf {
        val id = editor.selection.singleOrNull()
        val line = id?.let(editor::feature)?.geometry as? LineString
        if (line == null || editor.draft != null) return@derivedStateOf features()
        val location = line.length() * state.stationFraction
        if (location <= Length.Zero) return@derivedStateOf features()
        features(Feature(line.slice(Length.Zero, location), null))
      }
    }
  LineLayer(
    id = "shape-station-slice",
    source = rememberGeoJsonSource(data, synchronous),
    color = const(colors.tertiary),
    width = const(4.dp),
    cap = const(LineCap.Round),
    join = const(LineJoin.Round),
  )
}

/** The outline a simplify scrub started from, dashed, while the scrub lasts. */
@Composable
@MaplibreComposable
internal fun SimplifyGhostLayers(state: FeatureEditingState) {
  val colors = MaterialTheme.colorScheme
  val scrub = state.simplifyScrub
  // The outline stays while it fades out after the scrub ends.
  var ghost by remember { mutableStateOf<Geometry?>(null) }
  LaunchedEffect(scrub) { if (scrub != null) ghost = scrub.original.geometry }
  val alpha by
    animateFloatAsState(
      if (scrub != null) 1f else 0f,
      tween(if (scrub != null) 120 else 200),
      label = "simplify ghost",
    )
  val data = remember(ghost) { features(ghost?.let { Feature(it, null) }) }
  LineLayer(
    id = "shape-simplify-ghost",
    source = rememberGeoJsonSource(data, synchronous),
    color = const(colors.outline.copy(alpha = 0.7f)),
    width = const(1.5.dp),
    dasharray = const(listOf(2, 2)),
    opacity = const(alpha),
    opacityTransition = instant,
  )
}

/** The circle a circle draft would create. */
@Composable
@MaplibreComposable
internal fun CirclePreviewLayers(state: FeatureEditingState) {
  val colors = MaterialTheme.colorScheme
  val editor = state.editor
  val data by
    remember(state) {
      derivedStateOf {
        if (editor.tool !== state.circleTool) return@derivedStateOf features()
        val draft = editor.draft ?: return@derivedStateOf features()
        val center = draft.positions.singleOrNull() ?: return@derivedStateOf features()
        val cursor = draft.cursor ?: return@derivedStateOf features()
        val radius = distance(center, cursor)
        if (!radius.isPositive) return@derivedStateOf features()
        features(Feature(circle(center, radius, steps = CIRCLE_STEPS), null))
      }
    }
  val source = rememberGeoJsonSource(data, synchronous)
  FillLayer(
    id = "shape-circle-preview-fill",
    source = source,
    color = const(colors.primary.copy(alpha = 0.15f)),
    outlineColor = const(colors.primary),
  )
  LineLayer(
    id = "shape-circle-preview-line",
    source = source,
    color = const(colors.primary),
    width = const(1.5.dp),
    dasharray = const(listOf(2, 2)),
  )
}

/** A ring on the vertex the pointer is snapped to. */
@Composable
@MaplibreComposable
internal fun SnapLayers(state: FeatureEditingState) {
  val colors = MaterialTheme.colorScheme
  val target = state.snapTarget
  val scale = remember { Animatable(1f) }
  LaunchedEffect(target) {
    if (target != null) {
      scale.snapTo(0.5f)
      scale.animateTo(1f, tween(100))
    }
  }
  val data = remember(target) { features(target?.let { Feature(Point(it), null) }) }
  CircleLayer(
    id = "shape-snap-ring",
    source = rememberGeoJsonSource(data, synchronous),
    radius = const(10.dp * scale.value),
    radiusTransition = instant,
    opacity = const(0f),
    strokeColor = const(colors.tertiary),
    strokeWidth = const(2.dp),
    pitchScale = const(CirclePitchScale.Viewport),
  )
}

/** The move, rotate, scale and radius discs, and the station dot. */
@Composable
@MaplibreComposable
internal fun FrameHandleLayers(state: FeatureEditingState) {
  val colors = MaterialTheme.colorScheme
  val editor = state.editor
  val data by
    remember(state) {
      derivedStateOf {
        GeoJsonData.Features(
          FeatureCollection(
            editor.handles.mapNotNull { handle ->
              val kind = handle.kind as? FrameHandle ?: return@mapNotNull null
              Feature(Point(handle.position), buildJsonObject { put(KIND, kind.key) })
            }
          )
        )
      }
    }
  val highlighted by
    remember(state) {
      derivedStateOf {
        state.frameGesture?.kind
          ?: ((editor.hover as? HandleHit)?.handle?.kind as? FrameHandle)
          ?: ((editor.activeHandle?.kind) as? FrameHandle)
      }
    }
  val grow by
    animateFloatAsState(
      if (highlighted != null) 1.15f else 1f,
      spring(dampingRatio = 0.6f, stiffness = 600f),
      label = "frame handle scale",
    )
  // The last highlighted handle keeps growing or shrinking after the highlight moves off it.
  var shown by remember { mutableStateOf(highlighted) }
  if (highlighted != null) SideEffect { shown = highlighted }
  val source = rememberGeoJsonSource(data, synchronous)
  val size = DpSize(FrameHandleSize, FrameHandleSize)
  val move = image(rememberFrameHandlePainter(Res.drawable.open_with_24px, colors), size)
  SymbolLayer(
    id = "shape-frame-handles",
    source = source,
    filter = feature[KIND] neq const(FrameHandle.Station.key),
    iconImage =
      switch(
        feature[KIND],
        case(FrameHandle.Move.key, move),
        case(
          FrameHandle.Rotate.key,
          image(rememberFrameHandlePainter(Res.drawable.rotate_right_24px, colors), size),
        ),
        case(
          FrameHandle.Scale.key,
          image(rememberFrameHandlePainter(Res.drawable.open_in_full_24px, colors), size),
        ),
        case(
          FrameHandle.Radius.key,
          image(rememberFrameHandlePainter(Res.drawable.straighten_24px, colors), size),
        ),
        fallback = move,
      ),
    iconSize =
      switch(
        condition(feature[KIND] eq const(shown?.key ?: ""), const(grow)),
        fallback = const(1f),
      ),
    iconAllowOverlap = const(true),
    iconIgnorePlacement = const(true),
    iconRotationAlignment = const(IconRotationAlignment.Viewport),
    iconPitchAlignment = const(IconPitchAlignment.Viewport),
  )
  CircleLayer(
    id = "shape-station-dot",
    source = source,
    filter = feature[KIND] eq const(FrameHandle.Station.key),
    radius = const(10.dp * (if (shown == FrameHandle.Station) grow else 1f)),
    radiusTransition = instant,
    color = const(colors.tertiary),
    strokeColor = const(colors.surface),
    strokeWidth = const(2.dp),
    pitchScale = const(CirclePitchScale.Viewport),
  )
}

private val FrameHandleSize = 30.dp

/** A disc with a soft shadow, a stroke, and a Material Symbols glyph. */
@Composable
internal fun rememberFrameHandlePainter(glyph: DrawableResource, colors: ColorScheme): Painter {
  val painter = painterResource(glyph)
  return remember(painter, colors.surface, colors.primary, colors.scrim) {
    object : Painter() {
      override val intrinsicSize = Size(30f, 30f)

      override fun DrawScope.onDraw() {
        val center = Offset(size.width / 2f, size.height / 2f)
        val unit = size.width / 30f
        drawCircle(colors.scrim.copy(alpha = 0.12f), 15f * unit, center)
        drawCircle(colors.scrim.copy(alpha = 0.13f), 14f * unit, center)
        drawCircle(colors.surface, 13f * unit, center)
        drawCircle(colors.primary, 12f * unit, center, style = Stroke(2f * unit))
        val glyphSize = 16f * unit
        translate(center.x - glyphSize / 2f, center.y - glyphSize / 2f) {
          with(painter) {
            draw(Size(glyphSize, glyphSize), colorFilter = ColorFilter.tint(colors.primary))
          }
        }
      }
    }
  }
}
