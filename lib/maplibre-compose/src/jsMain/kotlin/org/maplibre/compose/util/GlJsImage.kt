package org.maplibre.compose.util

import androidx.compose.ui.graphics.ImageBitmap
import js.buffer.ArrayBuffer
import js.objects.unsafeJso
import js.typedarrays.Uint8Array
import org.maplibre.compose.gljs.StyleImageData
import web.dom.document
import web.html.HTMLCanvasElement

/**
 * The alpha stays straight, unlike the MapLibre Native path: GL JS uploads style images with
 * `UNPACK_PREMULTIPLY_ALPHA_WEBGL` on, so premultiplying here would apply it twice.
 */
internal fun ImageBitmap.toGlJsImage(): StyleImageData {
  val pixels = Uint8Array<ArrayBuffer>(width * height * 4)
  writeStraightRgba(pixels.asDynamic())
  return unsafeJso {
    width = this@toGlJsImage.width.toDouble()
    height = this@toGlJsImage.height.toDouble()
    data = pixels
  }
}

/**
 * Encodes a Compose bitmap as a PNG `data:` URL. GL JS names an image source's image by URL and
 * offers no entry point for raw pixels, and this is a real encode rather than a copy.
 */
internal fun ImageBitmap.toDataUrl(): String {
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

/** [ImageBitmap.readPixels] hands back straight-alpha ARGB, so this is a channel reorder. */
private fun ImageBitmap.writeStraightRgba(target: dynamic) {
  val argb = IntArray(width * height)
  readPixels(argb)
  argb.forEachIndexed { index, pixel ->
    val offset = index * 4
    target[offset] = (pixel ushr 16) and 0xFF
    target[offset + 1] = (pixel ushr 8) and 0xFF
    target[offset + 2] = pixel and 0xFF
    target[offset + 3] = (pixel ushr 24) and 0xFF
  }
}
