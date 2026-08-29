package org.maplibre.compose.util

import androidx.compose.ui.graphics.ImageBitmap
import js.buffer.ArrayBuffer
import js.objects.unsafeJso
import js.typedarrays.Uint8Array
import kotlin.js.unsafeCast
import org.maplibre.compose.gljs.StyleImageData
import org.maplibre.compose.gljs.setUint8At
import web.dom.document
import web.html.HTMLCanvasElement

private fun encodePngDataUrl(
  canvas: HTMLCanvasElement,
  pixels: Uint8Array<ArrayBuffer>,
  width: Int,
  height: Int,
): String =
  js(
    """{
      var ctx = canvas.getContext('2d');
      if (!ctx) throw new Error('The browser would not give a 2D context');
      var image = ctx.createImageData(width, height);
      image.data.set(pixels);
      ctx.putImageData(image, 0, 0);
      return canvas.toDataURL();
    }"""
  )

/** Alpha stays straight: GL JS uploads style images with `UNPACK_PREMULTIPLY_ALPHA_WEBGL` on. */
internal fun ImageBitmap.toGlJsImage(): StyleImageData {
  val pixels = Uint8Array<ArrayBuffer>(width * height * 4)
  writeStraightRgba(pixels)
  return unsafeJso {
    width = this@toGlJsImage.width.toDouble()
    height = this@toGlJsImage.height.toDouble()
    data = pixels
  }
}

/** Encodes a Compose bitmap as a PNG `data:` URL. */
internal fun ImageBitmap.toDataUrl(): String {
  val canvas = document.createElement("canvas").unsafeCast<HTMLCanvasElement>()
  canvas.width = width
  canvas.height = height
  val pixels = Uint8Array<ArrayBuffer>(width * height * 4)
  writeStraightRgba(pixels)
  return encodePngDataUrl(canvas, pixels, width, height)
}

/** [ImageBitmap.readPixels] hands back straight-alpha ARGB. */
private fun ImageBitmap.writeStraightRgba(target: Uint8Array<ArrayBuffer>) {
  val argb = IntArray(width * height)
  readPixels(argb)
  argb.forEachIndexed { index, pixel ->
    val offset = index * 4
    setUint8At(target, offset, (pixel ushr 16) and 0xFF)
    setUint8At(target, offset + 1, (pixel ushr 8) and 0xFF)
    setUint8At(target, offset + 2, pixel and 0xFF)
    setUint8At(target, offset + 3, (pixel ushr 24) and 0xFF)
  }
}
