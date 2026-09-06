package org.maplibre.compose.map

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.colorspace.ColorSpace
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.DesiredStyleRevision
import org.maplibre.compose.style.RecordingStyleBinding
import org.maplibre.compose.style.StyleBinding

internal class FakeSnapshotterAdapter(
  private val prepare: suspend (BaseStyle, MapSnapshotRequest) -> StyleBinding = { _, _ ->
    RecordingStyleBinding()
  },
  private val capture: suspend (MapSnapshotRequest, DesiredStyleRevision) -> ImageBitmap =
    { request, _ ->
      FakeImageBitmap(request.width, request.height)
    },
  private val cancel: suspend () -> SnapshotterEngineDisposition = {
    SnapshotterEngineDisposition.RETAINED
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
    revision: DesiredStyleRevision,
  ): ImageBitmap = capture.invoke(request, revision)

  override suspend fun cancelActiveCapture(): SnapshotterEngineDisposition = cancel.invoke()

  override suspend fun close() = close.invoke()
}

internal class FakeImageBitmap(override val width: Int, override val height: Int) : ImageBitmap {
  override val colorSpace: ColorSpace = ColorSpaces.Srgb
  override val hasAlpha: Boolean = true
  override val config: ImageBitmapConfig = ImageBitmapConfig.Argb8888

  override fun readPixels(
    buffer: IntArray,
    startX: Int,
    startY: Int,
    width: Int,
    height: Int,
    bufferOffset: Int,
    stride: Int,
  ) = Unit

  override fun prepareToDraw() = Unit
}
