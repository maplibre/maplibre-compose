package org.maplibre.compose.demoapp.demos.snapshotter

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Shares through a [FileProvider]-backed send intent and saves through the system document picker,
 * so no storage permission is needed on any supported API level.
 */
internal class AndroidSnapshotSharer(private val context: Context) : SnapshotSharer {
  /** The composable supplies the save launcher on every composition. */
  var saveLauncher: ActivityResultLauncher<String>? = null
  private var pendingSave: CompletableDeferred<Uri?>? = null

  private fun resolves(intent: Intent): Boolean =
    context.packageManager.resolveActivity(intent, 0) != null

  override val canShare: Boolean
    get() = resolves(Intent(Intent.ACTION_SEND).setType("image/png"))

  // Hosts like Wear and TV can have no activity handling ACTION_CREATE_DOCUMENT at all.
  override val canSave: Boolean
    get() = resolves(Intent(Intent.ACTION_CREATE_DOCUMENT).setType("image/png"))

  override suspend fun share(image: ImageBitmap, fileName: String): SnapshotActionResult {
    return withContext(Dispatchers.IO) {
      try {
        val directory = File(context.cacheDir, "snapshots").apply { mkdirs() }
        // The cache keeps only the latest capture.
        directory.listFiles()?.forEach { it.delete() }
        val file = File(directory, fileName)
        file.writeBytes(image.toPngBytes())
        val uri =
          FileProvider.getUriForFile(context, "${context.packageName}.snapshotprovider", file)
        val send =
          Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
          }
        context.startActivity(Intent.createChooser(send, null))
        // The chooser reports neither a selection nor a dismissal, so launching it is all that
        // can be reported.
        SnapshotActionResult.Completed("Share chooser opened")
      } catch (error: CancellationException) {
        throw error
      } catch (error: Throwable) {
        SnapshotActionResult.Failed
      }
    }
  }

  override suspend fun save(image: ImageBitmap, fileName: String): SnapshotActionResult {
    val launcher = saveLauncher ?: return SnapshotActionResult.Failed
    if (pendingSave != null) return SnapshotActionResult.Failed
    val bytes = withContext(Dispatchers.IO) { image.toPngBytes() }
    val result = CompletableDeferred<Uri?>()
    pendingSave = result
    // Clear the pending deferred on any exceptional exit, or a failed launch blocks every
    // later Save at the re-entry guard.
    val uri: Uri?
    try {
      launcher.launch(fileName)
      uri = result.await()
    } finally {
      pendingSave = null
    }
    if (uri == null) return SnapshotActionResult.Cancelled
    return withContext(Dispatchers.IO) {
      try {
        context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
          ?: return@withContext SnapshotActionResult.Failed
        SnapshotActionResult.Completed()
      } catch (error: CancellationException) {
        throw error
      } catch (error: Throwable) {
        SnapshotActionResult.Failed
      }
    }
  }

  /** Receives the document picker result. Null means the user left without picking. */
  fun onSaveResult(uri: Uri?) {
    pendingSave?.complete(uri)
    pendingSave = null
  }

  private fun ImageBitmap.toPngBytes(): ByteArray {
    val stream = ByteArrayOutputStream()
    check(asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, stream))
    return stream.toByteArray()
  }
}

@Composable
internal actual fun rememberSnapshotSharer(): SnapshotSharer {
  val context = LocalContext.current
  val sharer = remember(context) { AndroidSnapshotSharer(context) }
  val launcher =
    rememberLauncherForActivityResult(
      contract = ActivityResultContracts.CreateDocument("image/png"),
      onResult = sharer::onSaveResult,
    )
  SideEffect { sharer.saveLauncher = launcher }
  return sharer
}
