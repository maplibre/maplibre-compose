package org.maplibre.compose.map

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.colorspace.ColorSpace
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.RecordingStyleBinding
import org.maplibre.compose.style.StyleBinding
import org.maplibre.compose.style.StyleSnapshot

internal class FakeSnapshotterAdapter(
  private val prepare: suspend (BaseStyle, MapSnapshotRequest) -> StyleBinding = { _, _ ->
    RecordingStyleBinding()
  },
  private val capture: suspend (MapSnapshotRequest, StyleSnapshot) -> ImageBitmap = { request, _ ->
    FakeImageBitmap(request.width, request.height)
  },
  private val cancel: suspend () -> SnapshotterEngineDisposition = {
    SnapshotterEngineDisposition.Retained
  },
  private val close: suspend () -> Unit = {},
) : SnapshotterAdapter {
  override suspend fun prepare(
    baseStyle: BaseStyle,
    baseStyleRevision: Long,
    request: MapSnapshotRequest,
  ): SnapshotPreparation =
    SnapshotPreparation(prepare.invoke(baseStyle, request), viewportFor(request))

  override suspend fun capture(
    request: MapSnapshotRequest,
    revision: StyleSnapshot,
  ): ImageBitmap = capture.invoke(request, revision)

  override suspend fun cancelActiveCapture(): SnapshotterEngineDisposition = cancel.invoke()

  override suspend fun close() = close.invoke()
}

/** Reads as transparent black, or as [pixels] in straight-alpha ARGB when given. */
internal class FakeImageBitmap(
  override val width: Int,
  override val height: Int,
  private val pixels: IntArray? = null,
) : ImageBitmap {
  override val colorSpace: ColorSpace = ColorSpaces.Srgb
  override val hasAlpha: Boolean = true
  override val config: ImageBitmapConfig = ImageBitmapConfig.Argb8888

  var reads = 0
    private set

  override fun readPixels(
    buffer: IntArray,
    startX: Int,
    startY: Int,
    width: Int,
    height: Int,
    bufferOffset: Int,
    stride: Int,
  ) {
    reads++
    val pixels = pixels ?: return
    for (y in 0 until height) {
      for (x in 0 until width) {
        buffer[bufferOffset + y * stride + x] = pixels[(startY + y) * this.width + startX + x]
      }
    }
  }

  override fun prepareToDraw() = Unit
}
