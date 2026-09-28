package org.maplibre.compose.util

import js.buffer.ArrayBuffer
import js.objects.unsafeJso
import js.typedarrays.Int8Array
import js.typedarrays.Uint8Array
import js.typedarrays.Uint8ClampedArray
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import org.maplibre.compose.gljs.StyleImageData
import web.images.ImageData

/**
 * Straight-alpha RGBA8. MapLibre GL JS premultiplies at texture upload: an image source's texture
 * and a style image set `UNPACK_PREMULTIPLY_ALPHA_WEBGL`, and the location indicator's shader
 * premultiplies. Scaling the colour channels here would darken a translucent image twice.
 *
 * The views that [imageData] and [styleImageData] return share these bytes, which nothing writes
 * after construction; GL JS only reads them.
 */
internal actual class EnginePixels(
  actual val width: Int,
  actual val height: Int,
  private val rgba: ByteArray,
) {
  actual fun toStraightArgb(): IntArray =
    IntArray(width * height) { index ->
      val offset = index * 4
      ((rgba[offset + 3].toInt() and 0xFF) shl 24) or
        ((rgba[offset].toInt() and 0xFF) shl 16) or
        ((rgba[offset + 1].toInt() and 0xFF) shl 8) or
        (rgba[offset + 2].toInt() and 0xFF)
    }

  /** For `ImageSource.updateImage`, which keeps the view and copies it at each texture upload. */
  fun imageData(): ImageData =
    ImageData(Uint8ClampedArray(bytes.buffer, bytes.byteOffset, bytes.byteLength), width, height)

  /** For `addImage`, which copies the bytes, and for the location indicator's texture upload. */
  fun styleImageData(): StyleImageData = unsafeJso {
    width = this@EnginePixels.width.toDouble()
    height = this@EnginePixels.height.toDouble()
    data = Uint8Array(bytes.buffer, bytes.byteOffset, bytes.byteLength)
  }

  // Kotlin/JS represents a ByteArray as an Int8Array.
  private val bytes: Int8Array<ArrayBuffer>
    get() = rgba.unsafeCast<Int8Array<ArrayBuffer>>()

  private val hash by lazy { 31 * (31 * width + height) + rgba.contentHashCode() }

  override fun equals(other: Any?): Boolean =
    this === other ||
      other is EnginePixels &&
        hash == other.hash &&
        width == other.width &&
        height == other.height &&
        rgba.contentEquals(other.rgba)

  override fun hashCode(): Int = hash
}

internal actual fun enginePixels(width: Int, height: Int, straightArgb: IntArray): EnginePixels {
  val rgba = ByteArray(width * height * 4)
  for (index in 0 until width * height) {
    val pixel = straightArgb[index]
    val offset = index * 4
    rgba[offset] = (pixel ushr 16).toByte()
    rgba[offset + 1] = (pixel ushr 8).toByte()
    rgba[offset + 2] = pixel.toByte()
    rgba[offset + 3] = (pixel ushr 24).toByte()
  }
  return EnginePixels(width, height, rgba)
}

internal actual val enginePixelsContext: CoroutineContext = EmptyCoroutineContext
