package org.maplibre.compose.demoapp.demos.snapshotter

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import org.maplibre.compose.map.MapSnapshotRequest

/** Aspect ratio presets for the capture frame. [ratio] is width over height; null is freeform. */
internal enum class SnapshotAspect(val label: String, val ratio: Float?) {
  Free("Free", null),
  Square("1:1", 1f),
  Classic("4:3", 4f / 3f),
  Photo("3:2", 3f / 2f),
  Wide("16:9", 16f / 9f),
}

internal sealed interface CaptureStatus {
  data object Ready : CaptureStatus

  data object Capturing : CaptureStatus

  data class Failed(val message: String) : CaptureStatus
}

/** A finished capture plus the frame it was taken from, in map pixel coordinates. */
internal data class CapturedSnapshot(
  val image: ImageBitmap,
  val request: MapSnapshotRequest,
  val frame: Rect,
  val index: Int,
) {
  val fileName: String
    get() = if (index == 1) "maplibre-snapshot.png" else "maplibre-snapshot-$index.png"
}

internal enum class SnapshotAction {
  Share,
  Save,
}

/** The frame's draggable corners, with the direction each one pulls from the center. */
internal enum class FrameCorner(val signX: Float, val signY: Float) {
  TopLeft(-1f, -1f),
  TopRight(1f, -1f),
  BottomLeft(-1f, 1f),
  BottomRight(1f, 1f),
}

/** Frame edge clearance and smallest frame the handles can reach, in dp. */
internal val FrameMargin = 20.dp
internal val FrameMinSize = 96.dp

internal class SnapshotterDemoState {
  var aspect by mutableStateOf(SnapshotAspect.Free)
  var status by mutableStateOf<CaptureStatus>(CaptureStatus.Ready)
  var captured by mutableStateOf<CapturedSnapshot?>(null)
  var flashTick by mutableIntStateOf(0)
  var sheetOpen by mutableStateOf(false)
}

/** Fits the frame to the available area, preserving a selected ratio even on small hosts. */
internal fun fitFrameToSafeArea(size: DpSize, safe: DpSize, aspect: SnapshotAspect): DpSize {
  val maxWidth = (safe.width - FrameMargin * 2).coerceAtLeast(0.dp)
  val maxHeight = (safe.height - FrameMargin * 2).coerceAtLeast(0.dp)
  val ratio = aspect.ratio
  if (ratio == null)
    return DpSize(
      size.width.coerceIn(minOf(FrameMinSize, maxWidth), maxWidth),
      size.height.coerceIn(minOf(FrameMinSize, maxHeight), maxHeight),
    )
  val maximum = minOf(maxWidth, maxHeight * ratio)
  val minimum = minOf(maximum, maxOf(FrameMinSize, FrameMinSize * ratio))
  val width = size.width.coerceIn(minimum, maximum)
  return DpSize(width, width / ratio)
}

internal fun resizedFrame(
  current: DpSize,
  corner: FrameCorner,
  drag: DpOffset,
  safe: DpSize,
  aspect: SnapshotAspect,
): DpSize {
  val width = (current.width + drag.x * 2f * corner.signX).coerceAtLeast(0.dp)
  val height = (current.height + drag.y * 2f * corner.signY).coerceAtLeast(0.dp)
  val ratio = aspect.ratio
  val size =
    if (ratio != null && abs(drag.y.value) * ratio > abs(drag.x.value))
      DpSize(height * ratio, height)
    else DpSize(width, height)
  return fitFrameToSafeArea(size, safe, aspect)
}

internal fun centeredFrame(size: DpSize, safe: DpSize): Rect =
  Rect(
    Offset((safe.width - size.width).value / 2f, (safe.height - size.height).value / 2f),
    Size(size.width.value, size.height.value),
  )
