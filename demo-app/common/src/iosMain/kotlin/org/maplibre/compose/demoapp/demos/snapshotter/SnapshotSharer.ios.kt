@file:OptIn(ExperimentalForeignApi::class)

package org.maplibre.compose.demoapp.demos.snapshotter

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import kotlin.coroutines.resume
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.refTo
import kotlinx.coroutines.suspendCancellableCoroutine
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import platform.CoreGraphics.CGRectGetHeight
import platform.CoreGraphics.CGRectGetWidth
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Photos.PHAssetChangeRequest
import platform.Photos.PHPhotoLibrary
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication
import platform.UIKit.UIImage
import platform.UIKit.UIPopoverPresentationController
import platform.UIKit.UIViewController
import platform.UIKit.UIWindowScene
import platform.objc.sel_registerName
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fwrite

/**
 * Shares through the system share sheet and saves to the photo library, which the
 * NSPhotoLibraryAddUsageDescription entry in the demo's Info.plist covers. The PNG lands in a
 * temporary file first, so share targets receive a named file instead of an anonymous image.
 */
internal class IosSnapshotSharer : SnapshotSharer {
  override val canShare = true
  override val canSave = true

  override suspend fun share(image: ImageBitmap, fileName: String): SnapshotActionResult {
    val path = image.writeToTempFile(fileName) ?: return SnapshotActionResult.Failed
    try {
      val presenter = topViewController() ?: return SnapshotActionResult.Failed
      val sheet =
        UIActivityViewController(
          activityItems = listOf(NSURL.fileURLWithPath(path)),
          applicationActivities = null,
        )
      // iPad share sheets present as popovers and crash without a source. The
      // popoverPresentationController property is hidden in this SDK's Kotlin/Native UIKit mapping,
      // so it is read through the Objective-C runtime instead.
      val popover =
        sheet.performSelector(sel_registerName("popoverPresentationController"))
          as? UIPopoverPresentationController
      if (popover != null) {
        popover.sourceView = presenter.view
        popover.sourceRect =
          CGRectMake(
            x = CGRectGetWidth(presenter.view.bounds) / 2,
            y = CGRectGetHeight(presenter.view.bounds) / 2,
            width = 0.0,
            height = 0.0,
          )
      }
      return suspendCancellableCoroutine { continuation ->
        sheet.completionWithItemsHandler = { _, completed, _, error ->
          sheet.completionWithItemsHandler = null
          if (continuation.isActive) {
            continuation.resume(
              when {
                error != null -> SnapshotActionResult.Failed
                completed -> SnapshotActionResult.Completed()
                else -> SnapshotActionResult.Cancelled
              }
            )
          }
        }
        continuation.invokeOnCancellation {
          sheet.dismissViewControllerAnimated(flag = false, completion = null)
        }
        presenter.presentViewController(sheet, animated = true, completion = null)
      }
    } finally {
      // The sheet has consumed the file by the time the handler resumes (or the action is
      // cancelled); don't let indexed captures accumulate in the temporary directory.
      NSFileManager.defaultManager.removeItemAtPath(path, error = null)
    }
  }

  override suspend fun save(image: ImageBitmap, fileName: String): SnapshotActionResult {
    val path = image.writeToTempFile(fileName) ?: return SnapshotActionResult.Failed
    try {
      val uiImage = UIImage.imageWithContentsOfFile(path) ?: return SnapshotActionResult.Failed
      // performChanges reports denial and write errors, which UIImageWriteToSavedPhotosAlbum would
      // silently swallow without a completion target.
      return suspendCancellableCoroutine { continuation ->
        PHPhotoLibrary.sharedPhotoLibrary()
          .performChanges(
            changeBlock = { PHAssetChangeRequest.creationRequestForAssetFromImage(uiImage) },
            completionHandler = { success, error ->
              if (continuation.isActive) {
                continuation.resume(
                  if (success && error == null) SnapshotActionResult.Completed("Photos")
                  else SnapshotActionResult.Failed
                )
              }
            },
          )
      }
    } finally {
      NSFileManager.defaultManager.removeItemAtPath(path, error = null)
    }
  }

  private fun ImageBitmap.writeToTempFile(fileName: String): String? {
    val bytes =
      Image.makeFromBitmap(asSkiaBitmap()).encodeToData(EncodedImageFormat.PNG)?.bytes
        ?: return null
    val path = NSTemporaryDirectory() + fileName
    val file = fopen(path, "wb") ?: return null
    try {
      val written = fwrite(bytes.refTo(0), 1u, bytes.size.toULong(), file)
      return if (written.toInt() == bytes.size) path else null
    } finally {
      fclose(file)
    }
  }

  private fun topViewController(): UIViewController? {
    for (scene in UIApplication.sharedApplication.connectedScenes) {
      val windowScene = scene as? UIWindowScene ?: continue
      val root = windowScene.keyWindow?.rootViewController ?: continue
      var top = root
      while (top.presentedViewController != null) top = top.presentedViewController!!
      return top
    }
    return null
  }
}

@Composable
internal actual fun rememberSnapshotSharer(): SnapshotSharer = remember { IosSnapshotSharer() }
