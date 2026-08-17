package org.maplibre.compose.offline

import org.maplibre.compose.util.toBoundingBox
import org.maplibre.compose.util.toByteArray
import org.maplibre.compose.util.toMLNCoordinateBounds
import org.maplibre.compose.util.toNSData
import org.maplibre.spatialk.geojson.Geometry
import org.maplibre.spatialk.geojson.toJson
import platform.Foundation.NSError
import platform.Foundation.NSURL
import platform.Foundation.NSUTF8StringEncoding
import platform.posix.UINT64_MAX
import swiftPMImport.org.maplibre.compose.lib.maplibre.compose.MLNOfflinePackProgress
import swiftPMImport.org.maplibre.compose.lib.maplibre.compose.MLNOfflinePackStateActive
import swiftPMImport.org.maplibre.compose.lib.maplibre.compose.MLNOfflinePackStateComplete
import swiftPMImport.org.maplibre.compose.lib.maplibre.compose.MLNOfflinePackStateInactive
import swiftPMImport.org.maplibre.compose.lib.maplibre.compose.MLNOfflinePackStateInvalid
import swiftPMImport.org.maplibre.compose.lib.maplibre.compose.MLNOfflinePackStateUnknown
import swiftPMImport.org.maplibre.compose.lib.maplibre.compose.MLNOfflineRegionProtocol
import swiftPMImport.org.maplibre.compose.lib.maplibre.compose.MLNShape
import swiftPMImport.org.maplibre.compose.lib.maplibre.compose.MLNShapeOfflineRegion
import swiftPMImport.org.maplibre.compose.lib.maplibre.compose.MLNTilePyramidOfflineRegion

internal fun NSError.toOfflineManagerException() =
  OfflineManagerException(message = localizedDescription)

internal fun MLNOfflineRegionProtocol.toOfflinePackDefinition() =
  when (this) {
    is MLNTilePyramidOfflineRegion ->
      OfflinePackDefinition.TilePyramid(
        styleUrl = styleURL.toString(),
        bounds = bounds.toBoundingBox(),
        minZoom = minimumZoomLevel.toInt(),
        maxZoom = if (maximumZoomLevel.isInfinite()) null else maximumZoomLevel.toInt(),
      )
    is MLNShapeOfflineRegion ->
      OfflinePackDefinition.Shape(
        styleUrl = styleURL.toString(),
        shape =
          Geometry.fromJson(
            shape.geoJSONDataUsingEncoding(NSUTF8StringEncoding).toByteArray().decodeToString()
          ),
        minZoom = minimumZoomLevel.toInt(),
        maxZoom = if (maximumZoomLevel.isInfinite()) null else maximumZoomLevel.toInt(),
      )
    else -> error("Unknown MLNOfflineRegion type: $this")
  }

internal fun OfflinePackDefinition.toMLNOfflineRegion(): MLNOfflineRegionProtocol =
  when (this) {
    is OfflinePackDefinition.TilePyramid ->
      MLNTilePyramidOfflineRegion(
        styleURL = NSURL(string = styleUrl),
        bounds = bounds.toMLNCoordinateBounds(),
        fromZoomLevel = minZoom.toDouble(),
        toZoomLevel = maxZoom?.toDouble() ?: Double.POSITIVE_INFINITY,
      )
    is OfflinePackDefinition.Shape ->
      MLNShapeOfflineRegion(
        styleURL = NSURL(string = styleUrl),
        shape =
          MLNShape.shapeWithData(
            data = shape.toJson().encodeToByteArray().toNSData(),
            encoding = NSUTF8StringEncoding,
            error = null,
          )!!,
        fromZoomLevel = minZoom.toDouble(),
        toZoomLevel = maxZoom?.toDouble() ?: Double.POSITIVE_INFINITY,
      )
  }

internal fun MLNOfflinePackProgress.toDownloadProgress(state: Long) =
  when (state) {
    MLNOfflinePackStateUnknown -> DownloadProgress.Unknown
    MLNOfflinePackStateInvalid ->
      DownloadProgress.Error("Invalid", "The pack has already been removed!")
    MLNOfflinePackStateInactive,
    MLNOfflinePackStateActive,
    MLNOfflinePackStateComplete ->
      DownloadProgress.Healthy(
        completedResourceCount = countOfResourcesCompleted.toLong(),
        completedResourceBytes = countOfBytesCompleted.toLong(),
        completedTileCount = countOfTilesCompleted.toLong(),
        completedTileBytes = countOfTileBytesCompleted.toLong(),
        status =
          when (state) {
            MLNOfflinePackStateInactive -> DownloadStatus.Paused
            MLNOfflinePackStateActive -> DownloadStatus.Downloading
            MLNOfflinePackStateComplete -> DownloadStatus.Complete
            else -> error("impossible")
          },
        // UINT64_MAX when unknown
        isRequiredResourceCountPrecise = maximumResourcesExpected < UINT64_MAX,
        requiredResourceCount = countOfResourcesExpected.toLong(),
      )
    else -> error("Unknown OfflinePack state: $state")
  }
