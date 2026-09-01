package org.maplibre.compose.offline

import co.touchlab.kermit.Logger
import org.maplibre.compose.util.toBoundingBox
import org.maplibre.compose.util.toLatLngBounds
import org.maplibre.nativeffi.offline.OfflineRegionDefinition as FfiRegionDefinition
import org.maplibre.nativeffi.offline.OfflineRegionDownloadState
import org.maplibre.nativeffi.offline.OfflineRegionStatus
import org.maplibre.nativeffi.resource.ResourceErrorReason
import org.maplibre.spatialk.geojson.Geometry
import org.maplibre.spatialk.geojson.GeometryCollection
import org.maplibre.spatialk.geojson.toJson

/**
 * Whether downloaded packs include CJK glyphs.
 *
 * True here, unlike Android and iOS, because those platforms render ideographs from a local system
 * font. MapLibre Native's renderer takes a local font family too, but maplibre-native-ffi does not
 * expose it, so the glyphs have to come down with the pack.
 */
private const val INCLUDE_IDEOGRAPHS = true

internal fun OfflinePackDefinition.toFfiRegionDefinition(): FfiRegionDefinition =
  when (this) {
    is OfflinePackDefinition.TilePyramid ->
      FfiRegionDefinition.TilePyramid(
        styleUrl = styleUrl,
        bounds = bounds.toLatLngBounds(),
        minZoom = minZoom.toDouble(),
        maxZoom = maxZoom?.toDouble() ?: Double.POSITIVE_INFINITY,
        pixelRatio = pixelRatio,
        includeIdeographs = INCLUDE_IDEOGRAPHS,
      )
    is OfflinePackDefinition.Shape ->
      FfiRegionDefinition.GeometryRegion(
        styleUrl = styleUrl,
        geometry = shape.toJson().encodeToByteArray(),
        minZoom = minZoom.toDouble(),
        maxZoom = maxZoom?.toDouble() ?: Double.POSITIVE_INFINITY,
        pixelRatio = pixelRatio,
        includeIdeographs = INCLUDE_IDEOGRAPHS,
      )
  }

/**
 * The common representation of a region MapLibre already has in its database, or null when the FFI
 * reports it as `Unknown`, which carries no style URL and so cannot become an
 * [OfflinePackDefinition].
 */
internal fun FfiRegionDefinition.toOfflinePackDefinition(logger: Logger): OfflinePackDefinition? =
  when (this) {
    is FfiRegionDefinition.TilePyramid ->
      OfflinePackDefinition.TilePyramid(
        styleUrl = styleUrl,
        bounds = bounds.toBoundingBox(),
        pixelRatio = pixelRatio,
        minZoom = minZoom.toInt(),
        // MapLibre spells "no maximum" as infinity, which does not survive a conversion to Int.
        maxZoom = maxZoom.takeIf { it.isFinite() }?.toInt(),
      )
    is FfiRegionDefinition.GeometryRegion ->
      OfflinePackDefinition.Shape(
        styleUrl = styleUrl,
        shape = geometry.toGeoJsonGeometry(logger),
        pixelRatio = pixelRatio,
        minZoom = minZoom.toInt(),
        maxZoom = maxZoom.takeIf { it.isFinite() }?.toInt(),
      )
    else -> {
      logger.w { "Ignoring an offline region with an unrecognized definition: $this" }
      null
    }
  }

internal fun OfflineRegionStatus.toDownloadProgress(logger: Logger): DownloadProgress =
  DownloadProgress.Healthy(
    completedResourceCount = completedResourceCount,
    completedResourceBytes = completedResourceSize,
    completedTileCount = completedTileCount,
    completedTileBytes = completedTileSize,
    status =
      when {
        complete -> DownloadStatus.Complete
        downloadState == OfflineRegionDownloadState.ACTIVE -> DownloadStatus.Downloading
        downloadState == OfflineRegionDownloadState.INACTIVE -> DownloadStatus.Paused
        else -> {
          // Download states are value classes over Int rather than enums, so a newer native runtime
          // can report one this build has never seen.
          logger.w { "Unrecognized offline download state $downloadState; reporting it as paused" }
          DownloadStatus.Paused
        }
      },
    isRequiredResourceCountPrecise = requiredResourceCountIsPrecise,
    requiredResourceCount = requiredResourceCount,
  )

/**
 * The failure reason as [DownloadProgress.Error] spells it: the MapLibre Android SDK's
 * `OfflineRegionError` reason strings, so common code sees the same values on every platform.
 */
internal fun ResourceErrorReason.toDownloadErrorReason(): String =
  when (this) {
    ResourceErrorReason.NONE -> "REASON_SUCCESS"
    ResourceErrorReason.NOT_FOUND -> "REASON_NOT_FOUND"
    ResourceErrorReason.SERVER -> "REASON_SERVER"
    ResourceErrorReason.CONNECTION -> "REASON_CONNECTION"
    ResourceErrorReason.RATE_LIMIT -> "REASON_RATE_LIMIT"
    else -> "REASON_OTHER"
  }

private fun ByteArray.toGeoJsonGeometry(logger: Logger): Geometry = runCatching {
  Geometry.fromJson(decodeToString())
}
  .getOrElse {
    // An unreadable shape has no GeoJSON spelling; an empty collection keeps the pack listed and
    // deletable.
    logger.w(it) { "Offline region shape has no readable GeoJSON; reporting it as empty" }
    GeometryCollection<Geometry>(emptyList())
  }
