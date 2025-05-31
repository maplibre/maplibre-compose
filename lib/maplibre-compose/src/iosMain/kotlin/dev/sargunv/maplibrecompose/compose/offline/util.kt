package dev.sargunv.maplibrecompose.compose.offline

import cocoapods.MapLibre.MLNOfflinePackProgress
import cocoapods.MapLibre.MLNOfflinePackStateActive
import cocoapods.MapLibre.MLNOfflinePackStateComplete
import cocoapods.MapLibre.MLNOfflinePackStateInactive
import cocoapods.MapLibre.MLNOfflinePackStateInvalid
import cocoapods.MapLibre.MLNOfflinePackStateUnknown
import cocoapods.MapLibre.MLNOfflineRegionProtocol
import cocoapods.MapLibre.MLNShape
import cocoapods.MapLibre.MLNShapeOfflineRegion
import cocoapods.MapLibre.MLNTilePyramidOfflineRegion
import dev.sargunv.maplibrecompose.core.util.toBoundingBox
import dev.sargunv.maplibrecompose.core.util.toByteArray
import dev.sargunv.maplibrecompose.core.util.toMLNCoordinateBounds
import dev.sargunv.maplibrecompose.core.util.toNSData
import io.github.dellisd.spatialk.geojson.Geometry
import platform.Foundation.NSError
import platform.Foundation.NSURL
import platform.Foundation.NSUTF8StringEncoding
import platform.posix.UINT64_MAX

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
            data = shape.json().encodeToByteArray().toNSData(),
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
