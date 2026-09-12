package org.maplibre.compose.demoapp.demos.snapshotter

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import js.array.jsArrayOf
import js.objects.unsafeJso
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import web.blob.Blob
import web.dom.document
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
    val blob = image.toPngBlob() ?: return SnapshotActionResult.Failed
    val file = File(jsArrayOf(blob), fileName, unsafeJso<FilePropertyBag> { type = "image/png" })
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

private suspend fun ImageBitmap.toPngBlob(): Blob? = suspendCancellableCoroutine { continuation ->
  toCanvas().asDynamic().toBlob({ blob: Blob? -> continuation.resume(blob) }, "image/png")
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
