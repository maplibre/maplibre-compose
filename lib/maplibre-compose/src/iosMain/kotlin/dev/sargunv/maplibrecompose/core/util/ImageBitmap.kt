package dev.sargunv.maplibrecompose.core.util

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import kotlin.experimental.ExperimentalNativeApi
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo

@OptIn(ExperimentalNativeApi::class)
internal actual fun IntArray.toImageBitmap(width: Int, height: Int): ImageBitmap {
  val bmp = Bitmap()
  val info = ImageInfo(width, height, ColorType.RGBA_8888, ColorAlphaType.PREMUL, ColorSpace.sRGB)
  bmp.installPixels(
    info = info,
    pixels =
      this.foldIndexed(ByteArray(width * height * info.bytesPerPixel)) { index, acc, pixel ->
        acc.setIntAt(index, pixel)
        acc
      },
    rowBytes = info.minRowBytes,
  )
  return bmp.asComposeImageBitmap()
}
