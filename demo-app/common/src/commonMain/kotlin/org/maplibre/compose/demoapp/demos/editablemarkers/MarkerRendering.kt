package org.maplibre.compose.demoapp.demos.editablemarkers

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.materialkolor.dynamicColorScheme
import com.materialkolor.dynamiccolor.ColorSpec
import com.materialkolor.ktx.harmonizeWithPrimary
import kotlin.time.Duration
import org.jetbrains.compose.resources.painterResource
import org.maplibre.compose.demoapp.DemoStyle
import org.maplibre.compose.demoapp.generated.Res
import org.maplibre.compose.demoapp.generated.editable_marker_pin
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.image
import org.maplibre.compose.expressions.dsl.offset
import org.maplibre.compose.expressions.dsl.textOffset
import org.maplibre.compose.expressions.value.IconPitchAlignment
import org.maplibre.compose.expressions.value.IconRotationAlignment
import org.maplibre.compose.expressions.value.SymbolAnchor
import org.maplibre.compose.expressions.value.TranslateAnchor
import org.maplibre.compose.interaction.ClickResult
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.layers.SymbolLayer
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.GeoJsonOptions
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.style.TransitionOptions
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.Point

@Composable
internal fun MarkerLayers(
  marker: EditableMarker,
  motion: MarkerMotion,
  style: DemoStyle,
  onSelect: () -> Unit,
) {
  val theme = MaterialTheme.colorScheme
  // Compose drives each frame, so the map must not add another transition.
  val instant = TransitionOptions(Duration.ZERO)

  val colors = markerColorScheme(marker.color)
  val source =
    rememberGeoJsonSource(
      GeoJsonData.Features(Feature(geometry = Point(marker.position), properties = null)),
      options = GeoJsonOptions(synchronousUpdate = true),
    )

  // A soft shadow makes the lift during hover and drag visible against the map.
  CircleLayer(
    id = "editable-marker-shadow-${marker.id}",
    source = source,
    radius = const(motion.shadowRadius.dp),
    color = const(theme.scrim),
    opacity = const(motion.alpha * 0.18f),
    blur = const(0.7f),
    opacityTransition = instant,
    radiusTransition = instant,
  )

  // The halo marks selection without changing the pin's chosen color.
  CircleLayer(
    id = "editable-marker-halo-${marker.id}",
    source = source,
    radius = const((10f + 3f * motion.highlight).dp),
    color = const(colors.primary),
    opacity = const(motion.alpha * motion.highlight.coerceIn(0f, 1f) * 0.12f),
    strokeColor = const(colors.primary),
    strokeWidth = const(1.5.dp),
    strokeOpacity = const(motion.alpha * motion.highlight.coerceIn(0f, 1f) * 0.65f),
    radiusTransition = instant,
    opacityTransition = instant,
    strokeOpacityTransition = instant,
  )

  // Keep the icon and its live label in one symbol layer.
  SymbolLayer(
    id = "editable-marker-${marker.id}",
    source = source,
    iconImage = image(rememberMarkerPainter(colors), size = DpSize(40.dp, 48.dp)),
    iconAnchor = const(SymbolAnchor.Bottom),
    iconSize = const(motion.scale),
    iconRotate = const(motion.rotation),
    iconRotationAlignment = const(IconRotationAlignment.Viewport),
    iconPitchAlignment = const(IconPitchAlignment.Viewport),
    iconTranslate = offset(0.dp, motion.offsetY.dp),
    iconTranslateAnchor = const(TranslateAnchor.Viewport),
    iconTranslateTransition = instant,
    iconOpacity = const(motion.alpha),
    iconOpacityTransition = instant,
    iconAllowOverlap = const(true),
    iconIgnorePlacement = const(true),
    textField = const(marker.label),
    textFont = const(style.textFont),
    textSize = const(14.sp),
    textAnchor = const(SymbolAnchor.Top),
    textOffset = textOffset(0.dp, 12.dp),
    textColor = const(theme.onSurface),
    textHaloColor = const(theme.surface),
    textHaloWidth = const(2.dp),
    textOpacity = const(motion.alpha),
    textOpacityTransition = instant,
    textAllowOverlap = const(true),
    textIgnorePlacement = const(true),
    onClick = {
      onSelect()
      ClickResult.Consume
    },
  )
}

internal enum class MarkerColor(val label: String, val seed: Color? = null) {
  Theme("Theme"),
  Coral("Coral", Color(0xFFE96556)),
  Amber("Amber", Color(0xFFE8AD32)),
  Mint("Mint", Color(0xFF209D82)),
  Violet("Violet", Color(0xFF8855C9)),
}

@Composable
internal fun markerColorScheme(choice: MarkerColor): ColorScheme {
  val theme = MaterialTheme.colorScheme
  val seed = choice.seed ?: return theme
  return remember(seed, theme.primary, theme.surface) {
    dynamicColorScheme(
      seedColor = theme.harmonizeWithPrimary(seed),
      isDark = theme.surface.luminance() < 0.5f,
      specVersion = ColorSpec.SpecVersion.SPEC_2025,
    )
  }
}

@Composable
internal fun rememberMarkerPainter(colors: ColorScheme): Painter {
  val silhouette = painterResource(Res.drawable.editable_marker_pin)
  val outline = MaterialTheme.colorScheme.surface
  return remember(silhouette, colors.primary, colors.onPrimary, outline) {
    object : Painter() {
      override val intrinsicSize = Size(40f, 48f)

      override fun DrawScope.onDraw() {
        with(silhouette) { draw(size, colorFilter = ColorFilter.tint(outline)) }
        inset(horizontal = size.width * 0.055f, vertical = size.height * 0.045f) {
          with(silhouette) { draw(size, colorFilter = ColorFilter.tint(colors.primary)) }
        }
        val center = Offset(size.width / 2f, size.height * 0.4f)
        drawCircle(colors.onPrimary, size.width * 0.21f, center)
        drawCircle(colors.primary, size.width * 0.085f, center)
      }
    }
  }
}

internal data class MarkerMotion(
  val alpha: Float,
  val scale: Float,
  val rotation: Float,
  val offsetY: Float,
  val shadowRadius: Float,
  val highlight: Float,
)

@Composable
internal fun rememberMarkerMotion(
  marker: EditableMarker,
  selected: Boolean,
  hovered: Boolean,
  pressed: Boolean,
  dragging: Boolean,
  overTrash: Boolean,
  dragTilt: Float,
  onRemoved: () -> Unit,
): MarkerMotion {
  // Keep the source alive until the exit animation finishes.
  val presence = remember { Animatable(0f) }
  LaunchedEffect(marker.removing) {
    if (marker.removing) {
      presence.animateTo(0f, tween(220))
      onRemoved()
    } else presence.animateTo(1f, spring(dampingRatio = 0.48f, stiffness = 260f))
  }

  // Repeated selections and color changes can replay this feedback without changing selection.
  val rock = remember { Animatable(0f) }
  LaunchedEffect(marker.bounce) {
    if (marker.bounce > 0) {
      rock.snapTo(-14f)
      rock.animateTo(0f, spring(dampingRatio = 0.3f, stiffness = 420f))
    }
  }

  // Lift on hover/drag, compress on press, and shrink into the armed trash target.
  val lift by
    animateFloatAsState(
      when {
        dragging -> 18f
        pressed -> -2f
        hovered -> 6f
        selected -> 3f
        else -> 0f
      },
      spring(dampingRatio = 0.5f, stiffness = 360f),
    )
  val scale by
    animateFloatAsState(
      when {
        dragging && overTrash -> 0.75f
        pressed -> 0.86f
        dragging -> 1.18f
        hovered -> 1.12f
        selected -> 1.06f
        else -> 1f
      },
      spring(dampingRatio = 0.45f, stiffness = 500f),
    )
  val tilt by animateFloatAsState(if (dragging) dragTilt else 0f, spring(stiffness = 450f))
  val highlight by
    animateFloatAsState(if (selected || hovered) 1f else 0f, spring(stiffness = 350f))

  val alpha = presence.value.coerceIn(0f, 1f)
  return MarkerMotion(
    alpha = alpha,
    scale = (presence.value * scale).coerceAtLeast(0.01f),
    rotation = rock.value + tilt,
    offsetY = -lift - (1f - alpha) * 32f,
    shadowRadius = 7f + lift.coerceAtLeast(0f) * 0.15f,
    highlight = highlight,
  )
}
