package org.maplibre.compose.demoapp.demos.snapshotter

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import js.array.jsArrayOf
import js.buffer.ArrayBuffer
import js.objects.unsafeJso
import js.typedarrays.Uint8Array
import kotlin.js.JsString
import kotlin.js.Promise
import kotlinx.coroutines.await
import web.dom.document
import web.encoding.atob
import web.file.File
import web.file.FilePropertyBag
import web.html.HTMLAnchorElement
import web.html.HTMLCanvasElement
import web.navigator.navigator
import web.share.ShareData

/**
 * Saves with an anchor download and shares with the Web Share API when the browser can share files.
 */
internal class JsSnapshotSharer : SnapshotSharer {
  override val canShare = webShareSupported()
  override val canSave = true

  override suspend fun share(image: ImageBitmap, fileName: String): SnapshotActionResult {
    // The file is built synchronously: awaiting an async encode first would spend the click's
    // transient user activation, and strict browsers would reject share() with NotAllowedError.
    val file = dataUrlToFile(image.toDataUrl(), fileName)
    val data = unsafeJso<ShareData> { files = jsArrayOf(file) }
    if (!navigator.canShare(data)) return SnapshotActionResult.Failed
    return when (asKotlinString(shareSnapshot(data).await())) {
      "completed" -> SnapshotActionResult.Completed()
      "cancelled" -> SnapshotActionResult.Cancelled
      else -> SnapshotActionResult.Failed
    }
  }

  override suspend fun save(image: ImageBitmap, fileName: String): SnapshotActionResult {
    val anchor = document.createElement("a") as HTMLAnchorElement
    anchor.href = image.toDataUrl()
    anchor.download = fileName
    document.body.appendChild(anchor)
    anchor.click()
    anchor.remove()
    return SnapshotActionResult.Completed("your downloads")
  }
}

@Composable
internal actual fun rememberSnapshotSharer(): SnapshotSharer = remember { JsSnapshotSharer() }

private fun webShareSupported(): Boolean {
  if (!hasWebShare()) return false
  // Some browsers share text but not files; only a real File probe tells them apart.
  val probe = dataUrlToFile(TinyPngDataUrl, "probe.png")
  val data = unsafeJso<ShareData> { files = jsArrayOf(probe) }
  return try {
    navigator.canShare(data)
  } catch (error: Throwable) {
    false
  }
}

/** A 1×1 transparent PNG, used to probe whether the browser can share files at all. */
private const val TinyPngDataUrl =
  "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="

/** Decodes a PNG data URL into a [File] without any async step. */
private fun dataUrlToFile(dataUrl: String, fileName: String): File {
  val binary = atob(dataUrl.substringAfter(','))
  val bytes = Uint8Array<ArrayBuffer>(binary.length)
  for (index in binary.indices) {
    setByte(bytes, index, binary[index].code)
  }
  return File(
    jsArrayOf(bytes.buffer),
    fileName,
    unsafeJso<FilePropertyBag> { type = "image/png" },
  )
}

private fun ImageBitmap.toDataUrl(): String {
  val canvas = document.createElement("canvas").unsafeCast<HTMLCanvasElement>()
  canvas.width = width
  canvas.height = height
  val argb = IntArray(width * height)
  readPixels(argb)
  val rgba = Uint8Array<ArrayBuffer>(argb.size * 4)
  argb.forEachIndexed { index, pixel ->
    val offset = index * 4
    setByte(rgba, offset, (pixel ushr 16) and 0xff)
    setByte(rgba, offset + 1, (pixel ushr 8) and 0xff)
    setByte(rgba, offset + 2, pixel and 0xff)
    setByte(rgba, offset + 3, (pixel ushr 24) and 0xff)
  }
  return encodeCanvas(canvas, rgba)
}

private fun encodeCanvas(canvas: HTMLCanvasElement, rgba: Uint8Array<*>): String =
  js(
    "{ const ctx = canvas.getContext('2d'); if (!ctx) throw new Error('The browser would not give a 2D context'); ctx.putImageData(new ImageData(new Uint8ClampedArray(rgba.buffer),canvas.width,canvas.height),0,0); return canvas.toDataURL('image/png'); }",
  )

private fun setByte(bytes: Uint8Array<*>, index: Int, value: Int): Unit =
  js("{ bytes[index] = value }")

private fun hasWebShare(): Boolean =
  js("typeof navigator.share === 'function' && typeof navigator.canShare === 'function'")

private fun shareSnapshot(data: ShareData): Promise<JsString> =
  js(
    "navigator.share(data).then(() => 'completed', error => error.name === 'AbortError' ? 'cancelled' : 'failed')",
  )

private fun asKotlinString(value: JsString): String = js("value")
