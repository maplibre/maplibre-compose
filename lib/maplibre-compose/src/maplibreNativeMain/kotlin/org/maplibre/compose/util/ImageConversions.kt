package org.maplibre.compose.util

import androidx.compose.ui.graphics.ImageBitmap
import org.maplibre.compose.style.ImageSnapshot
import org.maplibre.nativeffi.render.PremultipliedRgba8Image

/**
 * Converts a Compose bitmap to the tightly packed premultiplied RGBA8 MapLibre expects.
 *
 * [ImageBitmap.readPixels] hands back straight-alpha ARGB, so the colour channels are scaled by
 * alpha here; skipping that leaves translucent images with bright fringes.
 */
internal fun ImageBitmap.toPremultipliedRgba8(): PremultipliedRgba8Image {
  val argb = IntArray(width * height)
  readPixels(argb)
  return premultipliedRgba8(width, height) { argb[it] }
}

internal fun ImageSnapshot.toPremultipliedRgba8(): PremultipliedRgba8Image =
  premultipliedRgba8(width, height, ::pixelAt)

private inline fun premultipliedRgba8(
  width: Int,
  height: Int,
  pixelAt: (Int) -> Int,
): PremultipliedRgba8Image {
  val rgba = ByteArray(width * height * 4)
  for (index in 0 until width * height) {
    val pixel = pixelAt(index)
    val alpha = (pixel ushr 24) and 0xFF
    val offset = index * 4
    rgba[offset] = (((pixel ushr 16) and 0xFF) * alpha / 255).toByte()
    rgba[offset + 1] = (((pixel ushr 8) and 0xFF) * alpha / 255).toByte()
    rgba[offset + 2] = ((pixel and 0xFF) * alpha / 255).toByte()
    rgba[offset + 3] = alpha.toByte()
  }
  return PremultipliedRgba8Image(width = width, height = height, stride = width * 4, pixels = rgba)
}
