package org.maplibre.compose.demoapp.demos

import androidx.compose.foundation.clickable
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.maplibre.compose.demoapp.design.SectionHeader
import org.maplibre.compose.map.rememberMapRuntime
import org.maplibre.compose.material3.OfflinePackListItem
import org.maplibre.compose.offline.OfflinePackDefinition
import org.maplibre.spatialk.geojson.BoundingBox

@Composable
actual fun OfflineRegionSection(region: BoundingBox, styleUrl: String, packName: String) {
  val offlineManager = rememberMapRuntime().offlineManager
  val pixelRatio = LocalDensity.current.density
  val scope = rememberCoroutineScope()
  val metadata = remember(packName) { packName.encodeToByteArray() }
  val pack = offlineManager.packs.firstOrNull { it.metadata?.contentEquals(metadata) == true }
  var creating by remember { mutableStateOf(false) }
  var errorMessage by remember { mutableStateOf<String?>(null) }

  SectionHeader("Offline")
  if (pack != null) {
    OfflinePackListItem(pack = pack, offlineManager = offlineManager) { Text(packName) }
  } else {
    ListItem(
      headlineContent = { Text("Download this region") },
      supportingContent = {
        Text(
          text = errorMessage ?: "For use without a network",
          color =
            if (errorMessage != null) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
      },
      colors = ListItemDefaults.colors(containerColor = Color.Transparent),
      modifier =
        Modifier.clickable(enabled = !creating) {
          creating = true
          errorMessage = null
          scope.launch {
            try {
              val newPack =
                offlineManager.create(
                  definition =
                    OfflinePackDefinition.TilePyramid(
                      styleUrl = styleUrl,
                      bounds = region,
                      pixelRatio = pixelRatio,
                      minZoom = 12,
                      maxZoom = 15,
                    ),
                  metadata = metadata,
                )
              offlineManager.resume(newPack)
            } catch (e: CancellationException) {
              throw e
            } catch (e: Exception) {
              errorMessage = e.message ?: "Couldn't download this region"
            } finally {
              creating = false
            }
          }
        },
    )
  }
}
