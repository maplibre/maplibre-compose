@file:Suppress("unused")

package org.maplibre.compose.docsnippets

import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.launch
import org.maplibre.compose.map.DefaultMapRuntime
import org.maplibre.compose.offline.DownloadProgress
import org.maplibre.compose.offline.OfflinePackDefinition
import org.maplibre.spatialk.geojson.BoundingBox

@Composable
fun Offline() {
  // #region manager
  val offlineManager = DefaultMapRuntime.instance.offlineManager
  // #endregion manager
  val scope = rememberCoroutineScope()
  val pixelRatio = LocalDensity.current.density

  // #region create
  Button(
    onClick = {
      scope.launch {
        val pack =
          offlineManager.create(
            definition =
              OfflinePackDefinition.TilePyramid(
                styleUrl = "https://tiles.openfreemap.org/styles/liberty",
                bounds = BoundingBox(west = -123.0, south = 47.0, east = -122.0, north = 48.0),
                pixelRatio = pixelRatio,
                minZoom = 10,
                maxZoom = 14,
              ),
            metadata = "Seattle".encodeToByteArray(),
          )
        offlineManager.resume(pack)
      }
    }
  ) {
    Text("Download Seattle")
  }
  // #endregion create

  // #region progress
  for (pack in offlineManager.packs) {
    val name = pack.metadata?.decodeToString() ?: "Unnamed"
    when (val progress = pack.downloadProgress) {
      is DownloadProgress.Healthy ->
        Text("$name: ${progress.completedResourceCount} resources, ${progress.status}")
      is DownloadProgress.Error -> Text("$name: ${progress.message}")
      is DownloadProgress.TileLimitExceeded -> Text("$name: tile limit ${progress.limit}")
      is DownloadProgress.Unknown -> Text("$name: waiting for status")
    }
  }
  // #endregion progress

  // #region delete
  for (pack in offlineManager.packs) {
    Button(onClick = { scope.launch { offlineManager.delete(pack) } }) {
      Text("Delete ${pack.metadata?.decodeToString()}")
    }
  }
  // #endregion delete
}
