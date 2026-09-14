package org.maplibre.compose.demoapp.demos.snapshotter

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap

/** How a share or save action ended, so the sheet can say what happened. */
internal sealed interface SnapshotActionResult {
  /** The action finished. [detail] names the destination when the platform reports one. */
  data class Completed(val detail: String? = null) : SnapshotActionResult

  /** The user left the platform flow without finishing it. */
  data object Cancelled : SnapshotActionResult

  /** The platform flow itself failed. */
  data object Failed : SnapshotActionResult
}

/** Sends a captured snapshot to other apps or to device storage. */
internal interface SnapshotSharer {
  /** Whether [share] can present the platform's share flow. */
  val canShare: Boolean

  /** Whether [save] can store the image on the device. */
  val canSave: Boolean

  /** Presents the platform share flow for [image] under [fileName]. */
  suspend fun share(image: ImageBitmap, fileName: String): SnapshotActionResult

  /** Stores [image] as [fileName], reporting where it landed in the result detail. */
  suspend fun save(image: ImageBitmap, fileName: String): SnapshotActionResult
}

@Composable internal expect fun rememberSnapshotSharer(): SnapshotSharer
