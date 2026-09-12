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
import kotlin.math.max
import kotlin.math.min
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

/** A finished capture plus the frame it was taken from, in map dp coordinates. */
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
  var aspect by mutableStateOf(SnapshotAspect.Classic)

  /** Frame size in dp. [DpSize.Zero] until the overlay measures its first safe area. */
  var frameSize by mutableStateOf(DpSize.Zero)

  /** The frame rect in map dp coordinates, mirrored from the overlay for capture and reveal. */
  var frameBounds by mutableStateOf<Rect?>(null)

  var status by mutableStateOf<CaptureStatus>(CaptureStatus.Ready)
  var captured by mutableStateOf<CapturedSnapshot?>(null)
  var cleanupFailure by mutableStateOf<String?>(null)

  /** Increments when a capture starts, retriggering the shutter flash. */
  var flashTick by mutableIntStateOf(0)

  var sheetOpen by mutableStateOf(false)
  var captureCount by mutableIntStateOf(0)
  var runningAction by mutableStateOf<SnapshotAction?>(null)
  var actionMessage by mutableStateOf<String?>(null)
  var actionFailed by mutableStateOf(false)

  fun noteActionResult(action: SnapshotAction, result: SnapshotActionResult) {
    runningAction = null
    when (result) {
      is SnapshotActionResult.Completed -> {
        actionFailed = false
        actionMessage =
          when (action) {
            SnapshotAction.Share -> result.detail ?: "Shared"
            SnapshotAction.Save -> result.detail?.let { "Saved to $it" } ?: "Saved"
          }
      }
      SnapshotActionResult.Cancelled -> Unit
      SnapshotActionResult.Failed -> {
        actionFailed = true
        actionMessage =
          when (action) {
            SnapshotAction.Share -> "Could not share the snapshot"
            SnapshotAction.Save -> "Could not save the snapshot"
          }
      }
    }
  }
}

/** A comfortable default frame for [aspect], centered with [FrameMargin] clear of the safe area. */
internal fun defaultFrameSize(safe: DpSize, aspect: SnapshotAspect): DpSize {
  val maxWidth = (safe.width - FrameMargin * 2).coerceAtLeast(FrameMinSize)
  val maxHeight = (safe.height - FrameMargin * 2).coerceAtLeast(FrameMinSize)
  val ratio = aspect.ratio
  // Clamp through the same paths as refits and drags so the floor holds on tiny safe areas.
  return if (ratio == null) clampFrameSize(DpSize(maxWidth * 0.62f, maxHeight * 0.62f), safe)
  else clampFrameToRatio(DpSize(maxWidth * 0.62f, maxWidth * 0.62f / ratio), ratio, safe)
}

/** Clamps [size] so the centered frame stays on the safe area and at least [FrameMinSize]. */
internal fun clampFrameSize(size: DpSize, safe: DpSize): DpSize {
  val maxWidth = (safe.width - FrameMargin * 2).coerceAtLeast(FrameMinSize)
  val maxHeight = (safe.height - FrameMargin * 2).coerceAtLeast(FrameMinSize)
  return DpSize(
    width = size.width.coerceIn(FrameMinSize, maxWidth),
    height = size.height.coerceIn(FrameMinSize, maxHeight),
  )
}

/**
 * Resizes the centered frame from a [corner] drag by [drag]. The opposite corner mirrors the
 * motion, and a locked [aspect] keeps the ratio by following the dominant axis.
 */
internal fun resizedFrame(
  current: DpSize,
  corner: FrameCorner,
  drag: DpOffset,
  safe: DpSize,
  aspect: SnapshotAspect,
): DpSize {
  val dragged =
    DpSize(
      width = (current.width + drag.x * 2f * corner.signX).coerceAtLeast(1.dp),
      height = (current.height + drag.y * 2f * corner.signY).coerceAtLeast(1.dp),
    )
  val ratio = aspect.ratio ?: return clampFrameSize(dragged, safe)
  val widthChange = abs(dragged.width / current.width - 1f)
  val heightChange = abs(dragged.height / current.height - 1f)
  val locked =
    if (widthChange >= heightChange) DpSize(dragged.width, dragged.width / ratio)
    else DpSize(dragged.height * ratio, dragged.height)
  return clampFrameToRatio(locked, ratio, safe)
}

/** Re-fits the frame to a newly selected [aspect], keeping its current width where possible. */
internal fun refitFrame(current: DpSize, safe: DpSize, aspect: SnapshotAspect): DpSize {
  val ratio = aspect.ratio ?: return clampFrameSize(current, safe)
  return clampFrameToRatio(DpSize(current.width, current.width / ratio), ratio, safe)
}

/** Clamps [size] to the safe area, scaling both axes together so [ratio] survives the clamp. */
private fun clampFrameToRatio(size: DpSize, ratio: Float, safe: DpSize): DpSize {
  // Rebuild one axis from the ratio: after a tight-area fallback, size may no longer carry it.
  // When it does, the rebuild is lossless either way a drag resolved its dominant axis.
  val rebuilt = DpSize(size.width, size.width / ratio)
  val maxWidth = (safe.width - FrameMargin * 2).coerceAtLeast(FrameMinSize)
  val maxHeight = (safe.height - FrameMargin * 2).coerceAtLeast(FrameMinSize)
  val scale = min(1f, min(maxWidth / rebuilt.width, maxHeight / rebuilt.height))
  val fitted = DpSize(rebuilt.width * scale, rebuilt.height * scale)
  if (fitted.width >= FrameMinSize && fitted.height >= FrameMinSize) return fitted
  // Under the floor, grow back to the smallest size that keeps the ratio and clears both axes.
  val growScale = max(FrameMinSize / fitted.width, FrameMinSize / fitted.height)
  val grown = DpSize(fitted.width * growScale, fitted.height * growScale)
  // Only a genuinely tight safe area abandons the ratio.
  return if (grown.width <= maxWidth && grown.height <= maxHeight) grown
  else clampFrameSize(fitted, safe)
}

/** Clamps [size] to the safe area, keeping [aspect]'s ratio when it has one. */
internal fun fitFrameToSafeArea(size: DpSize, safe: DpSize, aspect: SnapshotAspect): DpSize =
  aspect.ratio?.let { clampFrameToRatio(size, it, safe) } ?: clampFrameSize(size, safe)

/** The centered rect of a frame of [size] inside a safe area of [safe]. */
internal fun centeredFrame(size: DpSize, safe: DpSize): Rect {
  val origin = Offset((safe.width - size.width).value / 2f, (safe.height - size.height).value / 2f)
  return Rect(origin, Size(size.width.value, size.height.value))
}
