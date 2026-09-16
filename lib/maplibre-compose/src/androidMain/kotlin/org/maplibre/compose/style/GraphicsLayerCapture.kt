package org.maplibre.compose.style

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.toSize
import java.nio.ByteBuffer
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

internal actual suspend fun GraphicsLayer.captureImage(
  density: Density,
  layoutDirection: LayoutDirection,
): ImageBitmap =
  if (Build.VERSION.SDK_INT >= 28) toImageBitmap()
  else captureWithImageReader(density, layoutDirection)

// Compose's API 22-27 capture feeds premultiplied Surface pixels to Bitmap.createBitmap(IntArray),
// which premultiplies them again. Copy the raw bytes instead.
// Upstream issue: https://issuetracker.google.com/issues/562108906
internal suspend fun GraphicsLayer.captureWithImageReader(
  density: Density,
  layoutDirection: LayoutDirection,
): ImageBitmap {
  val reader = ImageReader.newInstance(size.width, size.height, PixelFormat.RGBA_8888, 1)
  try {
    return suspendCancellableCoroutine { continuation ->
      reader.setOnImageAvailableListener(
        { available ->
          try {
            val bitmap = available.acquireLatestImage()?.use { it.copyBitmap() }
            if (bitmap != null) continuation.resume(bitmap.asImageBitmap())
          } catch (error: Exception) {
            continuation.resumeWithException(error)
          }
        },
        Handler(Looper.getMainLooper()),
      )
      val canvas = reader.surface.lockHardwareCanvas()
      try {
        canvas.drawColor(android.graphics.Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        CanvasDrawScope().draw(density, layoutDirection, Canvas(canvas), size.toSize()) {
          drawLayer(this@captureWithImageReader)
        }
      } finally {
        reader.surface.unlockCanvasAndPost(canvas)
      }
    }
  } finally {
    reader.setOnImageAvailableListener(null, null)
    reader.close()
  }
}

private fun Image.copyBitmap(): Bitmap {
  val plane = planes.single()
  check(plane.pixelStride == 4)
  val source = plane.buffer
  val row = ByteArray(width * plane.pixelStride)
  val pixels = ByteBuffer.allocate(row.size * height)
  for (y in 0 until height) {
    source.position(y * plane.rowStride)
    source.get(row)
    pixels.put(row)
  }
  pixels.rewind()
  return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
    copyPixelsFromBuffer(pixels)
  }
}
