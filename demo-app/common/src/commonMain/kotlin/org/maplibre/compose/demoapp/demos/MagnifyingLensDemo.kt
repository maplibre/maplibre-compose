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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.rememberCameraState
import org.maplibre.compose.demoapp.Demo
import org.maplibre.compose.demoapp.DemoAppState
import org.maplibre.compose.demoapp.LensRenderOptionsDefault
import org.maplibre.compose.demoapp.LensRenderSection
import org.maplibre.compose.demoapp.OpenFreeMap
import org.maplibre.compose.demoapp.design.SectionHeader
import org.maplibre.compose.demoapp.design.SegmentedRow
import org.maplibre.compose.demoapp.design.SliderRow
import org.maplibre.compose.map.GestureOptions
import org.maplibre.compose.map.MapOptions
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.overlay.MapOverlay
import org.maplibre.compose.overlay.MapOverlayScope
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.Position

/**
 * A second map floats over the shared one as a magnifying lens: clipped to a circle, dragged
 * around, and magnifying whatever part of the map it sits on. A
 * [CameraState][org.maplibre.compose.camera.CameraState] binds to a single map, so the lens has its
 * own; a one-way sync copies the main camera over, retargeted to the geographic position under the
 * lens's screen position with the magnification added. The lens map disables gestures, which keeps
 * the sync from looping.
 *
 * Compose modifiers do the lens look — clip, shadow, a sweep-gradient rim — and on Android they
 * reach the map only in texture mode, which is why the panel pairs the app's render mode with a
 * render mode for the lens itself.
 */
object MagnifyingLensDemo : Demo {
  override val name = "Magnifying lens"
  override val description = "A second map composited into a draggable lens with Compose modifiers."
  override val region = BoundingBox(west = -74.002, south = 40.748, east = -73.968, north = 40.768)
  override val preferredStyle = OpenFreeMap.Liberty

  override val camera =
    CameraPosition(target = Position(longitude = -73.9851, latitude = 40.7589), zoom = 15.0)

  private enum class LensShape(val label: String, val shape: Shape) {
    Circle("Circle", CircleShape),
    Square("Rounded square", RoundedCornerShape(24.dp)),
  }

  /** A sweep of grays that reads as a machined metal ring. */
  private val rimBrush =
    Brush.sweepGradient(
      listOf(Color(0xFF9E9E9E), Color(0xFFF5F5F5), Color(0xFF616161), Color(0xFF9E9E9E))
    )

  /** Zoom levels added to the main camera; each level doubles the scale. */
  private var magnification by mutableDoubleStateOf(2.0)
  private var lensSize by mutableFloatStateOf(220f)
  private var lensShape by mutableStateOf(LensShape.Circle)
  private var dragOffset by mutableStateOf(Offset.Zero)
  private var lensRenderOptions by mutableStateOf(LensRenderOptionsDefault)

  @Composable
  override fun MapOverlayScope.Overlay(state: DemoAppState) {
    val lensCamera = rememberCameraState()

    // The lens magnifies what is under it: its target is the geographic position at the lens's
    // own center, tracked here in the overlay's coordinates, which are the main map's screen
    // coordinates.
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
      // The glass: a streak of white fading away from the top left.
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
    LensRenderSection(state.settings, lensRenderOptions) { lensRenderOptions = it }

    SectionHeader("Lens")
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
