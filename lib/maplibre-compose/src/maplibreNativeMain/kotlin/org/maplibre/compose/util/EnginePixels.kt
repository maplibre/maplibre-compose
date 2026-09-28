package org.maplibre.compose.util

import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import org.maplibre.nativeffi.render.PremultipliedRgba8Image

/** The tightly packed premultiplied RGBA8 that MapLibre Native uploads. */
internal actual class EnginePixels(val ffi: PremultipliedRgba8Image) {
  actual val width: Int
    get() = ffi.width

  actual val height: Int
    get() = ffi.height

  actual fun toStraightArgb(): IntArray {
    val rgba = ffi.pixels
    return IntArray(width * height) { index ->
      val offset = index * 4
      val alpha = rgba[offset + 3].toInt() and 0xFF
      (alpha shl 24) or
        (unpremultiplyChannel(rgba[offset].toInt() and 0xFF, alpha) shl 16) or
        (unpremultiplyChannel(rgba[offset + 1].toInt() and 0xFF, alpha) shl 8) or
        unpremultiplyChannel(rgba[offset + 2].toInt() and 0xFF, alpha)
    }
  }

  private val hash by lazy { ffi.hashCode() }

  override fun equals(other: Any?): Boolean =
    this === other || other is EnginePixels && hash == other.hash && ffi == other.ffi

  override fun hashCode(): Int = hash
}

/**
 * [ImageBitmap.readPixels][androidx.compose.ui.graphics.ImageBitmap.readPixels] hands back
 * straight-alpha ARGB, so the colour channels are scaled by alpha here; skipping that leaves
 * translucent images with bright fringes.
 */
internal actual fun enginePixels(width: Int, height: Int, straightArgb: IntArray): EnginePixels {
  val rgba = ByteArray(width * height * 4)
  for (index in 0 until width * height) {
    val pixel = straightArgb[index]
    val alpha = (pixel ushr 24) and 0xFF
    val offset = index * 4
    rgba[offset] = premultiplyChannel((pixel ushr 16) and 0xFF, alpha).toByte()
    rgba[offset + 1] = premultiplyChannel((pixel ushr 8) and 0xFF, alpha).toByte()
    rgba[offset + 2] = premultiplyChannel(pixel and 0xFF, alpha).toByte()
    rgba[offset + 3] = alpha.toByte()
  }
  return EnginePixels(PremultipliedRgba8Image(width, height, stride = width * 4, pixels = rgba))
}

/** Rounds to nearest, as Skia and MapLibre GL JS do, so [unpremultiplyChannel] inverts it. */
internal fun premultiplyChannel(channel: Int, alpha: Int): Int = (channel * alpha + 127) / 255

/** For every premultiplied `channel <= alpha`, premultiplying the result gives `channel` back. */
internal fun unpremultiplyChannel(channel: Int, alpha: Int): Int =
  if (alpha == 0) 0 else (channel * 255 + alpha / 2) / alpha

internal actual val enginePixelsContext: CoroutineContext = Dispatchers.Default
