@file:Suppress("unused")

package org.maplibre.compose.docsnippets

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.launch
import org.maplibre.compose.map.DefaultMapRuntime
import org.maplibre.compose.material3.OfflinePackListItem
import org.maplibre.compose.offline.DownloadProgress
import org.maplibre.compose.offline.DownloadStatus
import org.maplibre.compose.offline.OfflinePack
import org.maplibre.compose.offline.OfflinePackDefinition
import org.maplibre.compose.offline.OfflineStorage
import org.maplibre.compose.offline.OfflineStorageState
import org.maplibre.compose.offline.offlineStorage
import org.maplibre.spatialk.geojson.BoundingBox

@Composable
fun Offline() {
  // #region storage
  val offlineStorage = DefaultMapRuntime.instance.offlineStorage
  // #endregion storage
  val scope = rememberCoroutineScope()

  // #region create
  val offlineState by offlineStorage.state.collectAsState()
  val pixelRatio = LocalDensity.current.density
  Button(
    enabled = offlineState is OfflineStorageState.Ready,
    onClick = {
      scope.launch {
        val pack =
          offlineStorage.create(
            definition =
              OfflinePackDefinition.TilePyramid(
                styleUrl = "https://tiles.openfreemap.org/styles/liberty",
                bounds = BoundingBox(west = -122.46, south = 47.48, east = -122.22, north = 47.74),
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

  // #region list
  val packs = (offlineState as? OfflineStorageState.Ready)?.packs.orEmpty()
  Column {
    for (pack in packs) {
      key(pack) {
        val metadata by pack.metadata.collectAsState()
        OfflinePackListItem(pack = pack, offlineStorage = offlineStorage) {
          Text(metadata?.decodeToString() ?: "Unnamed region")
        }
      }
    }
  }
  // #endregion list
}

// #region progress
@Composable
fun PackProgress(pack: OfflinePack) {
  val progress by pack.downloadProgress.collectAsState()
  when (val current = progress) {
    is DownloadProgress.Healthy -> {
      val fraction =
        if (current.requiredResourceCount > 0) {
          current.completedResourceCount.toFloat() / current.requiredResourceCount
        } else {
          0f
        }
      Column {
        LinearProgressIndicator(progress = { fraction })
        when (current.status) {
          DownloadStatus.Complete -> Text("Ready to use offline")
          DownloadStatus.Paused -> Text("Paused")
          else -> Text("Downloading")
        }
      }
    }
    is DownloadProgress.Error -> Text("Download failed: ${current.message}")
    is DownloadProgress.TileLimitExceeded -> Text("Too many tiles; the limit is ${current.limit}")
    else -> Text("Waiting for status")
  }
}

// #endregion progress

// #region delete
@Composable
fun DeletePackButton(offlineStorage: OfflineStorage, pack: OfflinePack) {
  val scope = rememberCoroutineScope()
  Button(onClick = { scope.launch { offlineStorage.delete(pack) } }) { Text("Delete") }
}
// #endregion delete
