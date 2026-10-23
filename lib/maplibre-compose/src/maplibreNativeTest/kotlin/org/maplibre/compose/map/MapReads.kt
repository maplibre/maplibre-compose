@file:OptIn(ExperimentalCoroutinesApi::class, ExperimentalTestApi::class)

package org.maplibre.compose.map

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.maplibre.nativeffi.generated.BoundOptions
import org.maplibre.nativeffi.generated.CameraOptions
import org.maplibre.nativeffi.generated.ImageStretch
import org.maplibre.nativeffi.generated.LatLng
import org.maplibre.nativeffi.generated.LogicalExtent
import org.maplibre.nativeffi.generated.MapHandle
import org.maplibre.nativeffi.generated.MapSnapshot
import org.maplibre.nativeffi.generated.MapTileOptions
import org.maplibre.nativeffi.generated.ProjectionMode
import org.maplibre.nativeffi.generated.StyleImageInfo
import org.maplibre.nativeffi.generated.StyleLayerInfo
import org.maplibre.nativeffi.generated.StyleLayerVisibility
import org.maplibre.nativeffi.generated.StyleSourceInfo

// Reads of a map's state for tests. An ordered query observes every command submitted before it;
// a snapshot field shows the state published by the latest command that ran.

/** The camera after every command submitted before this read. */
internal suspend fun MapHandle.camera(): CameraOptions = getCamera().await().camera

/** The snapshot published once every command submitted before this read has run. */
internal suspend fun MapHandle.snapshotAfterCommands(): MapSnapshot {
  // An ordered query runs after every earlier command, each of which publishes a snapshot.
  getCamera().await()
  return getSnapshot()
}

/** The logical size after every command submitted before this read. */
internal suspend fun MapHandle.size(): LogicalExtent = snapshotAfterCommands().view.extent

internal suspend fun MapHandle.tileOptions(): MapTileOptions = snapshotAfterCommands().tile

internal suspend fun MapHandle.projectionMode(): ProjectionMode =
  snapshotAfterCommands().view.projectionMode

internal suspend fun MapHandle.bounds(): BoundOptions = snapshotAfterCommands().bounds

internal val MapHandle.isFullyLoaded: Boolean
  get() = getSnapshot().fullyLoaded

internal suspend fun MapHandle.styleLayerIds(): List<String> =
  listStyleLayers().await().map { it.id }

internal suspend fun MapHandle.styleLayer(layerId: String): StyleLayerInfo? =
  getStyleLayer(layerId).await()

internal suspend fun MapHandle.styleLayerExists(layerId: String): Boolean =
  styleLayer(layerId) != null

internal suspend fun MapHandle.layerProperty(layerId: String, name: String): ByteArray? =
  getStyleLayerProperty(layerId, name).await()

internal suspend fun MapHandle.layerFilter(layerId: String): ByteArray? =
  getStyleLayerProperty(layerId, "filter").await()

internal suspend fun MapHandle.layerMinZoom(layerId: String): Double =
  checkNotNull(styleLayer(layerId)).minZoom

internal suspend fun MapHandle.layerMaxZoom(layerId: String): Double =
  checkNotNull(styleLayer(layerId)).maxZoom

internal suspend fun MapHandle.layerSourceId(layerId: String): String =
  checkNotNull(styleLayer(layerId)).sourceId.orEmpty()

internal suspend fun MapHandle.layerSourceLayer(layerId: String): String =
  checkNotNull(styleLayer(layerId)).sourceLayer.orEmpty()

internal suspend fun MapHandle.layerVisibility(layerId: String): StyleLayerVisibility =
  checkNotNull(styleLayer(layerId)).visibility

internal suspend fun MapHandle.styleSourceIds(): List<String> =
  listStyleSources().await().map { it.id }

internal suspend fun MapHandle.styleSourceInfo(sourceId: String): StyleSourceInfo? =
  getStyleSource(sourceId).await()

internal suspend fun MapHandle.styleImageInfo(imageId: String): StyleImageInfo? =
  getStyleImage(imageId).await()

internal suspend fun MapHandle.styleImageStretches(
  imageId: String,
): Pair<List<ImageStretch>, List<ImageStretch>>? =
  styleImageInfo(imageId)?.let { it.stretchX to it.stretchY }

internal suspend fun MapHandle.imageSourceCoordinates(sourceId: String): List<LatLng> =
  getImageSourceCoordinates(sourceId).await()

internal suspend fun MapHandle.loadedStyleJson(): ByteArray = getLoadedStyleJson().await()

/**
 * Runs [action] with this session's map, or returns null when the map does not exist. Tests only;
 * the reads in [org.maplibre.compose.map] wait for every command submitted before them.
 */
internal inline fun <T> MlnFfiMapSession.readMap(action: (MapHandle) -> T): T? =
  mapForTest?.let(action)

internal suspend fun MlnFfiMapSession.currentStyleLayerIds(): List<String> = readMap {
  it.styleLayerIds()
}
  .orEmpty()

internal suspend fun MlnFfiMapSession.styleImageInfo(imageId: String): StyleImageInfo? = readMap {
  it.styleImageInfo(imageId)
}

/**
 * Waits until the map's style layer ids satisfy [condition]. Each check reads the answer to the
 * layer query the previous check sent, so the Compose test thread never blocks on the map.
 */
internal fun ComposeUiTest.waitUntilStyleLayers(
  session: MlnFfiMapSession,
  timeoutMillis: Long,
  condition: (List<String>) -> Boolean,
) {
  var query: Deferred<List<StyleLayerInfo>>? = null
  waitUntil(timeoutMillis = timeoutMillis) {
    val answered = query?.takeIf { it.isCompleted }
    if (answered != null) {
      if (condition(answered.getCompleted().map { it.id })) return@waitUntil true
      query = null
    }
    if (query == null) query = session.mapForTest?.listStyleLayers()
    false
  }
}
