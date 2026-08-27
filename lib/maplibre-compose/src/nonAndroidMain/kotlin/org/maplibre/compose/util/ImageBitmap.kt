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
        // The color ints carry straight alpha, so the bytes premultiply to match the alpha type.
        val a = (pixel ushr 24) and 0xff
        acc[index * 4] = premultiply((pixel shr 16) and 0xff, a) // Red
        acc[index * 4 + 1] = premultiply((pixel shr 8) and 0xff, a) // Green
        acc[index * 4 + 2] = premultiply(pixel and 0xff, a) // Blue
        acc[index * 4 + 3] = a.toByte() // Alpha
        acc
      },
    rowBytes = info.minRowBytes,
  )
  return bmp.asComposeImageBitmap()
}

private fun premultiply(channel: Int, alpha: Int): Byte =
  if (alpha == 255) channel.toByte() else ((channel * alpha + 127) / 255).toByte()
