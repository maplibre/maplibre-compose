package org.maplibre.compose.util

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo

internal actual fun IntArray.toImageBitmap(width: Int, height: Int): ImageBitmap {
  val bmp = Bitmap()
  val info = ImageInfo(width, height, ColorType.RGBA_8888, ColorAlphaType.PREMUL, ColorSpace.sRGB)
  bmp.installPixels(
    info = info,
    pixels =
      this.foldIndexed(ByteArray(width * height * info.bytesPerPixel)) { index, acc, pixel ->
        acc[index * 4] = (pixel shr 24).toByte() // Alpha
        acc[index * 4 + 1] = (pixel shr 16).toByte() // Red
        acc[index * 4 + 2] = (pixel shr 8).toByte() // Green
        acc[index * 4 + 3] = pixel.toByte() // Blue
        acc
      },
    rowBytes = info.minRowBytes,
  )
  return bmp.asComposeImageBitmap()
}
