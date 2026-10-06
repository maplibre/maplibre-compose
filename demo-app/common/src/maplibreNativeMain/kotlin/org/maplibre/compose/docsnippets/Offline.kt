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
import org.maplibre.compose.offline.OfflineStorageState
import org.maplibre.spatialk.geojson.BoundingBox

@Composable
fun Offline() {
  // #region storage
  val offlineStorage = DefaultMapRuntime.instance.offlineStorage
  // #endregion storage
  val scope = rememberCoroutineScope()
  val pixelRatio = LocalDensity.current.density.toDouble()

  // #region create
  val offlineState by offlineStorage.state.collectAsState()
  Button(
    enabled = offlineState is OfflineStorageState.Ready,
    onClick = {
      scope.launch {
        val pack =
          offlineStorage.create(
            definition =
              OfflinePackDefinition.TilePyramid(
                styleUrl = "https://tiles.openfreemap.org/styles/liberty",
                bounds = BoundingBox(west = -123.0, south = 47.0, east = -122.0, north = 48.0),
                pixelRatio = pixelRatio,
                minZoom = 10.0,
                maxZoom = 14.0,
              ),
            metadata = "Seattle".encodeToByteArray(),
          )
        offlineStorage.resume(pack)
      }
    },
  ) {
    Text("Download Seattle")
  }
  // #endregion create

  // #region progress
  val packs = (offlineState as? OfflineStorageState.Ready)?.packs.orEmpty()
  when (val state = offlineState) {
    OfflineStorageState.Loading -> Text("Loading offline packs…")
    is OfflineStorageState.Failed -> Text(state.cause.message ?: "Could not load offline packs")
    is OfflineStorageState.Ready -> Unit
  }
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
        else -> Text(name)
      }
    }
  }
  // #endregion progress

  // #region delete
  for (pack in packs) {
    key(pack) {
      val metadata by pack.metadata.collectAsState()
      Button(onClick = { scope.launch { offlineStorage.delete(pack) } }) {
        Text("Delete ${metadata?.decodeToString()}")
      }
    }
  }
  // #endregion delete
}
