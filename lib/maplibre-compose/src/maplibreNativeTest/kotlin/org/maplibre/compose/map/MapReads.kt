package org.maplibre.compose.map

import kotlinx.coroutines.runBlocking
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

// Blocking reads of a map's state for tests. An ordered query observes every command submitted
// before it; a snapshot field shows the state published by the latest command that ran.

/** The camera after every command submitted before this read. */
internal val MapHandle.camera: CameraOptions
  get() = getCamera().let { completion -> runBlocking { completion.await() } }.camera

/** The snapshot published once every command submitted before this read has run. */
internal fun MapHandle.snapshotAfterCommands(): MapSnapshot {
  // An ordered query runs after every earlier command, each of which publishes a snapshot.
  getCamera().let { completion -> runBlocking { completion.await() } }
  return getSnapshot()
}

/** The logical size after every command submitted before this read. */
internal val MapHandle.size: LogicalExtent
  get() = snapshotAfterCommands().view.extent

internal val MapHandle.tileOptions: MapTileOptions
  get() = snapshotAfterCommands().tile

internal val MapHandle.projectionMode: ProjectionMode
  get() = snapshotAfterCommands().view.projectionMode

internal val MapHandle.bounds: BoundOptions
  get() = snapshotAfterCommands().bounds

internal val MapHandle.isFullyLoaded: Boolean
  get() = getSnapshot().fullyLoaded

internal fun MapHandle.styleLayerIds(): List<String> =
  listStyleLayers().let { completion -> runBlocking { completion.await() } }.map { it.id }

internal fun MapHandle.styleLayer(layerId: String): StyleLayerInfo? =
  getStyleLayer(layerId).let { completion -> runBlocking { completion.await() } }

internal fun MapHandle.styleLayerExists(layerId: String): Boolean = styleLayer(layerId) != null

internal fun MapHandle.layerProperty(layerId: String, name: String): ByteArray? =
  getStyleLayerProperty(layerId, name).let { completion -> runBlocking { completion.await() } }

internal fun MapHandle.layerFilter(layerId: String): ByteArray? =
  getStyleLayerProperty(layerId, "filter").let { completion -> runBlocking { completion.await() } }

internal fun MapHandle.layerMinZoom(layerId: String): Double =
  checkNotNull(styleLayer(layerId)).minZoom

internal fun MapHandle.layerMaxZoom(layerId: String): Double =
  checkNotNull(styleLayer(layerId)).maxZoom

internal fun MapHandle.layerSourceId(layerId: String): String =
  checkNotNull(styleLayer(layerId)).sourceId.orEmpty()

internal fun MapHandle.layerSourceLayer(layerId: String): String =
  checkNotNull(styleLayer(layerId)).sourceLayer.orEmpty()

internal fun MapHandle.layerVisibility(layerId: String): StyleLayerVisibility =
  checkNotNull(styleLayer(layerId)).visibility

internal fun MapHandle.styleSourceIds(): List<String> =
  listStyleSources().let { completion -> runBlocking { completion.await() } }.map { it.id }

internal fun MapHandle.styleSourceInfo(sourceId: String): StyleSourceInfo? =
  getStyleSource(sourceId).let { completion -> runBlocking { completion.await() } }

internal fun MapHandle.styleImageInfo(imageId: String): StyleImageInfo? =
  getStyleImage(imageId).let { completion -> runBlocking { completion.await() } }

internal fun MapHandle.styleImageStretches(
  imageId: String,
): Pair<List<ImageStretch>, List<ImageStretch>>? =
  styleImageInfo(imageId)?.let { it.stretchX to it.stretchY }

internal fun MapHandle.imageSourceCoordinates(sourceId: String): List<LatLng> =
  getImageSourceCoordinates(sourceId).let { completion -> runBlocking { completion.await() } }

internal fun MapHandle.loadedStyleJson(): ByteArray =
  getLoadedStyleJson().let { completion -> runBlocking { completion.await() } }

/**
 * Runs [action] with this session's map, or returns null when the map does not exist. Tests only;
 * the reads in [org.maplibre.compose.map] wait for every command submitted before them.
 */
internal fun <T> MlnFfiMapSession.readMap(action: (MapHandle) -> T): T? = mapForTest?.let(action)

internal fun MlnFfiMapSession.currentStyleLayerIds(): List<String> = readMap {
  it.styleLayerIds()
}
  .orEmpty()

internal fun MlnFfiMapSession.styleImageInfo(imageId: String): StyleImageInfo? = readMap {
  it.styleImageInfo(imageId)
}
