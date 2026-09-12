package org.maplibre.compose.demoapp.demos.snapshotter

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.util.lerp
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/** Docked thumbnail width; its height keeps the snapshot's aspect ratio. */
private val DockWidth = 128.dp
private val DockInset = 4.dp

/** The attribution button owns the bottom end corner, so the docked thumbnail floats above it. */
private val DockBottomClearance = 48.dp

/** How long the developed photo holds over the frame before flying to the dock. */
private const val RevealHoldMillis = 650L

/**
 * The capture celebration: the photo develops in place over the frame, holds, then springs to a
 * docked thumbnail at the bottom end of the safe area, above the attribution button. Tapping the
 * docked thumbnail opens the result sheet.
 */
@Composable
internal fun SnapshotFlight(
  state: SnapshotterDemoState,
  safe: DpSize,
  originDp: DpOffset,
  onOpen: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val shot = state.captured ?: return
  val motion = MaterialTheme.motionScheme
  val develop = remember { Animatable(0f) }
  val flight = remember { Animatable(0f) }

  LaunchedEffect(shot) {
    develop.snapTo(0f)
    flight.snapTo(0f)
    develop.animateTo(1f, motion.fastSpatialSpec())
    delay(RevealHoldMillis)
    flight.animateTo(1f, motion.slowSpatialSpec())
  }

  // The frame rect is stored in map coordinates; this overlay child is offset by originDp.
  val start = shot.frame.translate(Offset(-originDp.x.value, -originDp.y.value))
  // Tall Free-form captures must fit the height above the clearance as well as the width cap.
  val aspect = shot.request.height.toFloat() / shot.request.width.toFloat()
  val maxDockHeight =
    (safe.height.value - DockBottomClearance.value - DockInset.value).coerceAtLeast(48f)
  val dockWidth = min(DockWidth.value, min(safe.width.value * 0.34f, maxDockHeight / aspect))
  val dockHeight = dockWidth * aspect
  // The dock shares the attribution button's bottom end corner, so it flips in RTL.
  val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
  val dockLeft = if (isLtr) safe.width.value - dockWidth - DockInset.value else DockInset.value
  val dock =
    Rect(
      left = dockLeft,
      top = safe.height.value - dockHeight - DockBottomClearance.value,
      right = dockLeft + dockWidth,
      bottom = safe.height.value - DockBottomClearance.value,
    )
  val progress = flight.value
  val rect = lerpRect(start, dock, progress)
  // A parabolic tilt: level at takeoff and landing, a few degrees of bank mid-flight.
  val rotation = -7f * 4f * progress * (1f - progress)
  val developed = develop.value

  Surface(
    onClick = onOpen,
    enabled = progress == 1f,
    shape = MaterialTheme.shapes.medium,
    shadowElevation = lerp(2.dp, 8.dp, progress),
    modifier =
      modifier
        .offset {
          IntOffset(rect.left.dp.toPx().roundToInt(), rect.top.dp.toPx().roundToInt())
        }
        .size(rect.width.dp, rect.height.dp)
        .graphicsLayer {
          transformOrigin = TransformOrigin.Center
          rotationZ = rotation
          val scale = lerp(0.85f, 1f, developed)
          scaleX = scale
          scaleY = scale
          alpha = developed
        },
  ) {
    Box(Modifier.fillMaxSize()) {
      Image(
        bitmap = shot.image,
        contentDescription = "Latest map snapshot",
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize(),
      )
      // The developing photo carries a white matte that thins out as it docks.
      val matte = lerp(5.dp, 1.dp, progress)
      CanvasBorder(matte, Color.White.copy(alpha = lerp(0.95f, 0.4f, progress)))
    }
  }
}

@Composable
private fun CanvasBorder(width: Dp, color: Color) {
  Canvas(Modifier.fillMaxSize()) {
    drawRect(color = color, style = Stroke(width = width.toPx()))
  }
}

private fun lerpRect(start: Rect, stop: Rect, fraction: Float): Rect =
  Rect(
    left = lerp(start.left, stop.left, fraction),
    top = lerp(start.top, stop.top, fraction),
    right = lerp(start.right, stop.right, fraction),
    bottom = lerp(start.bottom, stop.bottom, fraction),
  )
