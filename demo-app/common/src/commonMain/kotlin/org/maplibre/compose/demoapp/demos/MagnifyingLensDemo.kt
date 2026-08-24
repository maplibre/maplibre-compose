package org.maplibre.compose.demoapp.demos

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import org.maplibre.compose.camera.rememberCameraState
import org.maplibre.compose.demoapp.Demo
import org.maplibre.compose.demoapp.DemoAppState
import org.maplibre.compose.demoapp.DemoDestination
import org.maplibre.compose.demoapp.DemoPointerPin
import org.maplibre.compose.demoapp.OpenFreeMap
import org.maplibre.compose.demoapp.center
import org.maplibre.compose.demoapp.design.SectionHeader
import org.maplibre.compose.demoapp.design.SegmentedRow
import org.maplibre.compose.demoapp.design.SliderRow
import org.maplibre.compose.demoapp.design.SwitchRow
import org.maplibre.compose.map.GestureOptions
import org.maplibre.compose.map.MapOptions
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.RenderOptions
import org.maplibre.compose.overlay.MapOverlay
import org.maplibre.compose.overlay.MapOverlayScope
import org.maplibre.spatialk.geojson.BoundingBox

/**
 * A second map floats over the shared one as a magnifying lens.
 *
 * A [CameraState][org.maplibre.compose.camera.CameraState] binds to a single map, so the lens has
 * its own, synced one-way from the main camera. The lens map disables gestures so the sync cannot
 * loop.
 *
 * On Android, Compose modifiers reach the map only in texture mode, so the panel exposes the lens
 * map's render mode.
 */
object MagnifyingLensDemo : Demo {
  override val name = "Magnifying lens"
  override val description = "A second map composited into a draggable lens with Compose modifiers."
  override val preferredStyle = OpenFreeMap.Liberty

  private val lensRegion =
    BoundingBox(west = -74.002, south = 40.748, east = -73.968, north = 40.768)

  override val destination = DemoDestination.FitBounds(lensRegion)
  override val pointerPin = DemoPointerPin(lensRegion.center, destination)

  private enum class LensShape(val label: String, val shape: Shape) {
    Circle("Circle", CircleShape),
    Square("Rounded square", RoundedCornerShape(24.dp)),
  }

  private val rimBrush =
    Brush.sweepGradient(
      listOf(Color(0xFF9E9E9E), Color(0xFFF5F5F5), Color(0xFF616161), Color(0xFF9E9E9E))
    )

  /** Each zoom level added doubles the scale, hence the 2×/4×/8× labels. */
  private var magnification by mutableDoubleStateOf(2.0)
  private var lensSize by mutableFloatStateOf(220f)
  private var lensShape by mutableStateOf(LensShape.Circle)
  private var dragOffset by mutableStateOf(Offset.Zero)
  private var lensRenderOptions by mutableStateOf(LensRenderOptionsDefault)
  private var lensDistortionEnabled by mutableStateOf(true)

  @Composable
  override fun MapOverlayScope.Overlay(state: DemoAppState) {
    val lensCamera = rememberCameraState()
    val lensSizePx = with(LocalDensity.current) { lensSize.dp.toPx() }

    // The overlay's coordinates are the main map's screen coordinates.
    var lensCenter by remember { mutableStateOf<Offset?>(null) }
    LaunchedEffect(Unit) {
      snapshotFlow {
        val position = cameraState.position
        val target =
          lensCenter?.let { cameraState.positionFromScreenLocation(it) } ?: position.target
        position.copy(target = target, zoom = position.zoom + magnification)
      }
        .collect { lensCamera.position = it }
    }

    Box(
      modifier =
        Modifier.align(Alignment.Center)
          .offset { IntOffset(dragOffset.x.roundToInt(), dragOffset.y.roundToInt()) }
          .onGloballyPositioned { coordinates ->
            lensCenter =
              coordinates.parentLayoutCoordinates
                ?.localBoundingBoxOf(coordinates, clipBounds = false)
                ?.center
          }
          .pointerInput(Unit) {
            detectDragGestures { change, dragAmount ->
              change.consume()
              dragOffset += dragAmount
            }
          }
          .size(lensSize.dp)
          .shadow(16.dp, lensShape.shape)
          .border(6.dp, rimBrush, lensShape.shape)
          .clip(lensShape.shape)
          .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
      MaplibreMap(
        modifier =
          if (lensDistortionEnabled) {
            Modifier.fillMaxSize().radialLensDistortion(lensSizePx)
          } else {
            Modifier.fillMaxSize()
          },
        baseStyle = state.selectedStyle.base,
        cameraState = lensCamera,
        options =
          MapOptions(
            renderOptions = lensRenderOptions,
            gestureOptions = GestureOptions.AllDisabled,
          ),
        contentWindowInsets = WindowInsets(0),
        overlay = MapOverlay.None,
      )
      Box(
        Modifier.fillMaxSize()
          .background(
            Brush.linearGradient(
              0.0f to Color.White.copy(alpha = 0.30f),
              0.4f to Color.White.copy(alpha = 0.05f),
              0.6f to Color.Transparent,
            )
          )
      )
    }
  }

  @Composable
  override fun Panel(state: DemoAppState) {
    LensRenderSection(lensRenderOptions) { lensRenderOptions = it }

    SectionHeader("Lens")
    SwitchRow("Lens distortion", lensDistortionEnabled) { lensDistortionEnabled = it }
    SegmentedRow(
      label = "Magnification",
      options = listOf(1.0, 2.0, 3.0),
      selected = magnification,
      optionLabel = { "${1 shl it.toInt()}×" },
      onSelect = { magnification = it },
    )
    SegmentedRow(
      label = "Shape",
      options = LensShape.entries,
      selected = lensShape,
      optionLabel = { it.label },
      onSelect = { lensShape = it },
    )
    SliderRow("Size", lensSize, 128f..360f) { lensSize = it }
  }
}

/**
 * The lens map's render mode row. Android presents the map as a texture or as a surface; every
 * other platform has one presentation, so the actuals there are empty.
 */
@Composable
expect fun LensRenderSection(lensOptions: RenderOptions, onLensChange: (RenderOptions) -> Unit)

/**
 * The lens map's initial render options: texture mode where a texture-versus-surface choice exists,
 * because Android applies Compose modifiers to the map only in texture mode.
 */
expect val LensRenderOptionsDefault: RenderOptions

/** Applies a convex-lens distortion where the platform supports runtime shaders. */
@Composable expect fun Modifier.radialLensDistortion(sizePx: Float): Modifier

internal const val LensShader =
  """
  uniform shader content;
  uniform float2 size;

  half4 main(float2 coord) {
    float2 center = size * 0.5;
    float2 delta = coord - center;
    float radius = min(size.x, size.y) * 0.5;
    float distanceFromCenter = min(length(delta) / radius, 1.0);
    float scale = 0.82 + 0.18 * distanceFromCenter * distanceFromCenter;
    return content.eval(center + delta * scale);
  }
"""
