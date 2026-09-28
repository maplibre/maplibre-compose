package org.maplibre.compose.util

import androidx.compose.ui.graphics.ImageBitmap
import js.buffer.ArrayBuffer
import js.objects.unsafeJso
import js.typedarrays.Uint8Array
import org.maplibre.compose.gljs.StyleImageData
import org.maplibre.compose.style.ImageSnapshot
import web.dom.document
import web.html.HTMLCanvasElement

/** Alpha stays straight: GL JS uploads style images with `UNPACK_PREMULTIPLY_ALPHA_WEBGL` on. */
internal fun ImageBitmap.toGlJsImage(): StyleImageData {
  val argb = IntArray(width * height)
  readPixels(argb)
  return glJsImage(width, height) { argb[it] }
}

internal fun ImageSnapshot.toGlJsImage(): StyleImageData = glJsImage(width, height, ::pixelAt)

private inline fun glJsImage(
  imageWidth: Int,
  imageHeight: Int,
  pixelAt: (Int) -> Int,
): StyleImageData {
  val pixels = Uint8Array<ArrayBuffer>(imageWidth * imageHeight * 4)
  for (index in 0 until imageWidth * imageHeight) {
    val pixel = pixelAt(index)
    val offset = index * 4
    pixels.asDynamic()[offset] = (pixel ushr 16) and 0xFF
    pixels.asDynamic()[offset + 1] = (pixel ushr 8) and 0xFF
    pixels.asDynamic()[offset + 2] = pixel and 0xFF
    pixels.asDynamic()[offset + 3] = (pixel ushr 24) and 0xFF
  }
  return unsafeJso {
    width = imageWidth.toDouble()
    height = imageHeight.toDouble()
    data = pixels
  }
}

/** Encodes a Compose bitmap as a PNG `data:` URL. */
internal fun ImageBitmap.toDataUrl(): String = ImageSnapshot.capture(this).toDataUrl()

internal fun ImageSnapshot.toDataUrl(): String {
  val canvas = document.createElement("canvas").unsafeCast<HTMLCanvasElement>()
  canvas.width = width
  canvas.height = height
  val context = canvas.asDynamic().getContext("2d")
  check(context != null && context != undefined) {
    "The browser would not give a 2D context for encoding a ${width}x$height image"
  }
  val image = context.createImageData(width, height)
  writeStraightRgba(image.data)
  context.putImageData(image, 0, 0)
  return canvas.asDynamic().toDataURL().unsafeCast<String>()
}

/** Stored pixels have straight-alpha ARGB channels. */
private fun ImageSnapshot.writeStraightRgba(target: dynamic) {
  for (index in 0 until width * height) {
    val pixel = pixelAt(index)
    val offset = index * 4
    target[offset] = (pixel ushr 16) and 0xFF
    target[offset + 1] = (pixel ushr 8) and 0xFF
    target[offset + 2] = pixel and 0xFF
    target[offset + 3] = (pixel ushr 24) and 0xFF
  }
}
