package org.maplibre.compose.demoapp.demos.snapshotter

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.util.lerp
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

@Composable
internal fun SnapshotFlight(
  shot: CapturedSnapshot,
  dock: Rect?,
  onOpen: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val slot = dock ?: return
  val density = LocalDensity.current
  val motion = MaterialTheme.motionScheme
  val develop = remember { Animatable(0f) }
  val flight = remember { Animatable(0f) }

  LaunchedEffect(shot) {
    develop.snapTo(0f)
    flight.snapTo(0f)
    develop.animateTo(1f, motion.fastSpatialSpec())
    delay(650)
    flight.animateTo(1f, motion.slowSpatialSpec())
  }

  val aspect = shot.image.width.toFloat() / shot.image.height
  val width = min(slot.width, slot.height * aspect)
  val height = width / aspect
  val destination =
    Rect(
      slot.center.x - width / 2,
      slot.center.y - height / 2,
      slot.center.x + width / 2,
      slot.center.y + height / 2,
    )
  val progress = flight.value
  val rect = lerpRect(shot.frame, destination, progress)
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
        .absoluteOffset {
          IntOffset(rect.left.roundToInt(), rect.top.roundToInt())
        }
        .size(with(density) { rect.width.toDp() }, with(density) { rect.height.toDp() })
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
