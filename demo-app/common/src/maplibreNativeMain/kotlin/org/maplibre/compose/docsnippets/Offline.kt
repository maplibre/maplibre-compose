@file:Suppress("unused")

package org.maplibre.compose.docsnippets

import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
  val packs by offlineManager.packs.collectAsState()
  for (pack in packs) {
    key(pack) {
      val metadata by pack.metadata.collectAsState()
      val progress by pack.downloadProgress.collectAsState()
      val name = metadata?.decodeToString() ?: "Unnamed"
      when (val current = progress) {
        is DownloadProgress.Healthy ->
          Text("$name: ${current.completedResourceCount} resources, ${current.status}")
        is DownloadProgress.Error -> Text("$name: ${current.message}")
        is DownloadProgress.TileLimitExceeded -> Text("$name: tile limit ${current.limit}")
        is DownloadProgress.Unknown -> Text("$name: waiting for status")
      }
    }
  }
  // #endregion progress

  // #region delete
  for (pack in packs) {
    key(pack) {
      Button(onClick = { scope.launch { offlineManager.delete(pack) } }) {
        Text("Delete ${pack.metadata.value?.decodeToString()}")
      }
    }
  }
  // #endregion delete
}
