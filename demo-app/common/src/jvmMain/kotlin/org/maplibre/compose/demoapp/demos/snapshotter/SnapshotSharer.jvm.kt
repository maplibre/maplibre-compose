package org.maplibre.compose.demoapp.demos.snapshotter

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import java.awt.EventQueue
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image

/** Desktops have no share sheet; saving uses the platform's native file dialog. */
internal class JvmSnapshotSharer : SnapshotSharer {
  override val canShare = false
  override val canSave = true

  override suspend fun share(image: ImageBitmap, fileName: String): SnapshotActionResult =
    SnapshotActionResult.Failed

  override suspend fun save(image: ImageBitmap, fileName: String): SnapshotActionResult {
    val bytes =
      withContext(Dispatchers.IO) { image.toPngBytes() } ?: return SnapshotActionResult.Failed
    // AWT widgets belong on the event-dispatch thread; the native dialog pumps its own event
    // loop while it is open, so invokeAndWait returns when the user closes it.
    val target =
      withContext(Dispatchers.IO) {
        var chosen: File? = null
        EventQueue.invokeAndWait {
          val dialog = FileDialog(null as Frame?, "Save snapshot", FileDialog.SAVE)
          try {
            dialog.file = fileName
            dialog.isVisible = true
            val directory = dialog.directory
            val name = dialog.file
            if (directory != null && name != null) {
              chosen = File(directory, if (name.endsWith(".png")) name else "$name.png")
            }
          } finally {
            dialog.dispose()
          }
        }
        chosen
      } ?: return SnapshotActionResult.Cancelled
    return withContext(Dispatchers.IO) {
      try {
        target.writeBytes(bytes)
        SnapshotActionResult.Completed(target.absolutePath)
      } catch (error: Throwable) {
        SnapshotActionResult.Failed
      }
    }
  }

  private fun ImageBitmap.toPngBytes(): ByteArray? =
    Image.makeFromBitmap(asSkiaBitmap()).encodeToData(EncodedImageFormat.PNG)?.bytes
}

@Composable
internal actual fun rememberSnapshotSharer(): SnapshotSharer = remember { JvmSnapshotSharer() }
