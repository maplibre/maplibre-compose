package org.maplibre.compose.demoapp.demos.snapshotter

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
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

  override suspend fun save(image: ImageBitmap, fileName: String): SnapshotActionResult =
    withContext(Dispatchers.IO) {
      val bytes = image.toPngBytes() ?: return@withContext SnapshotActionResult.Failed
      val dialog = FileDialog(null as Frame?, "Save snapshot", FileDialog.SAVE)
      try {
        dialog.file = fileName
        // Blocks until the dialog closes, which is why this runs on IO.
        dialog.isVisible = true
        val directory = dialog.directory ?: return@withContext SnapshotActionResult.Cancelled
        val chosen = dialog.file ?: return@withContext SnapshotActionResult.Cancelled
        val name = if (chosen.endsWith(".png")) chosen else "$chosen.png"
        File(directory, name).writeBytes(bytes)
        SnapshotActionResult.Completed("$directory${File.separator}$name")
      } catch (error: Throwable) {
        SnapshotActionResult.Failed
      } finally {
        dialog.dispose()
      }
    }

  private fun ImageBitmap.toPngBytes(): ByteArray? =
    Image.makeFromBitmap(asSkiaBitmap()).encodeToData(EncodedImageFormat.PNG)?.bytes
}

@Composable
internal actual fun rememberSnapshotSharer(): SnapshotSharer = remember { JvmSnapshotSharer() }
