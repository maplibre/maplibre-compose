package org.maplibre.compose.demoapp.demos.snapshotter

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import js.array.jsArrayOf
import js.buffer.ArrayBuffer
import js.objects.unsafeJso
import js.typedarrays.Uint8Array
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
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
    return suspendCancellableCoroutine { continuation ->
      navigator
        .asDynamic()
        .share(data)
        .then(
          { continuation.resume(SnapshotActionResult.Completed()) },
          { error: dynamic ->
            val aborted = error.name as? String == "AbortError"
            continuation.resume(
              if (aborted) SnapshotActionResult.Cancelled else SnapshotActionResult.Failed
            )
          },
        )
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
  val navigator = navigator.asDynamic()
  return navigator.share != undefined && navigator.canShare != undefined
}

/** Decodes a PNG data URL into a [File] without any async step. */
private fun dataUrlToFile(dataUrl: String, fileName: String): File {
  val binary = atob(dataUrl.substringAfter(','))
  val bytes = Uint8Array<ArrayBuffer>(binary.length).asDynamic()
  for (index in binary.indices) {
    bytes[index] = binary[index].code
  }
  return File(
    jsArrayOf(bytes.buffer.unsafeCast<js.buffer.ArrayBuffer>()),
    fileName,
    unsafeJso<FilePropertyBag> { type = "image/png" },
  )
}

@Suppress("UnsafeCastFromDynamic")
private fun ImageBitmap.toDataUrl(): String =
  toCanvas().asDynamic().toDataURL("image/png").unsafeCast<String>()

private fun ImageBitmap.toCanvas(): HTMLCanvasElement {
  val canvas = document.createElement("canvas").unsafeCast<HTMLCanvasElement>()
  canvas.width = width
  canvas.height = height
  val context = canvas.asDynamic().getContext("2d")
  check(context != null && context != undefined) {
    "The browser would not give a 2D context for encoding a ${width}x$height image"
  }
  val imageData = context.createImageData(width, height)
  // ImageBitmap.readPixels hands back straight-alpha ARGB; canvases take RGBA bytes.
  val argb = IntArray(width * height)
  readPixels(argb)
  argb.forEachIndexed { index, pixel ->
    val offset = index * 4
    imageData.data[offset] = (pixel ushr 16) and 0xFF
    imageData.data[offset + 1] = (pixel ushr 8) and 0xFF
    imageData.data[offset + 2] = pixel and 0xFF
    imageData.data[offset + 3] = (pixel ushr 24) and 0xFF
  }
  context.putImageData(imageData, 0, 0)
  return canvas
}
