@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package org.maplibre.compose.demoapp.demos.snapshotter

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import kotlin.coroutines.resume
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import platform.AppKit.NSModalResponseOK
import platform.AppKit.NSSavePanel
import platform.Foundation.NSData
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSURL
import platform.Foundation.create
import platform.Foundation.writeToURL
import platform.UniformTypeIdentifiers.UTTypePNG

internal class MacosSnapshotSharer : SnapshotSharer {
  override val canShare = false
  override val canSave = true

  override suspend fun share(image: ImageBitmap, fileName: String): SnapshotActionResult =
    SnapshotActionResult.Failed

  override suspend fun save(image: ImageBitmap, fileName: String): SnapshotActionResult {
    val bytes =
      withContext(Dispatchers.Default) {
        val skiaImage = Image.makeFromBitmap(image.asSkiaBitmap())
        try {
          val data = skiaImage.encodeToData(EncodedImageFormat.PNG)
          try {
            data?.bytes
          } finally {
            data?.close()
          }
        } finally {
          skiaImage.close()
        }
      } ?: return SnapshotActionResult.Failed
    val target = chooseDestination(fileName) ?: return SnapshotActionResult.Cancelled
    return withContext(Dispatchers.Default) {
      val saved = bytes.usePinned {
        NSData.create(bytes = it.addressOf(0), length = bytes.size.toULong())
          .writeToURL(target, atomically = true)
      }
      if (saved) SnapshotActionResult.Completed(target.path) else SnapshotActionResult.Failed
    }
  }

  private suspend fun chooseDestination(fileName: String): NSURL? =
    withContext(Dispatchers.Main) {
      val panel =
        NSSavePanel.savePanel().apply {
          title = "Save snapshot"
          nameFieldStringValue = fileName
          allowedContentTypes = listOf(UTTypePNG)
          allowsOtherFileTypes = false
          canCreateDirectories = true
        }
      suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation {
          NSOperationQueue.mainQueue.addOperationWithBlock { panel.cancel(null) }
        }
        panel.beginWithCompletionHandler { response ->
          if (continuation.isActive) {
            continuation.resume(if (response == NSModalResponseOK) panel.URL else null)
          }
        }
      }
    }
}

@Composable
internal actual fun rememberSnapshotSharer(): SnapshotSharer = remember { MacosSnapshotSharer() }
