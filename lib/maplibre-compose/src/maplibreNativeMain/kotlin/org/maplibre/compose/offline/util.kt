package org.maplibre.compose.offline

import org.maplibre.compose.logging.MapLog
import org.maplibre.compose.util.toBoundingBox
import org.maplibre.compose.util.toLatLngBounds
import org.maplibre.nativeffi.generated.OfflineGeometryRegionDefinition
import org.maplibre.nativeffi.generated.OfflineRegionDefinition as FfiRegionDefinition
import org.maplibre.nativeffi.generated.OfflineRegionDefinitionData
import org.maplibre.nativeffi.generated.OfflineRegionDownloadState
import org.maplibre.nativeffi.generated.OfflineRegionStatus
import org.maplibre.nativeffi.generated.OfflineTilePyramidRegionDefinition
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
private const val IncludeIdeographs = true

internal fun OfflinePackDefinition.toFfiRegionDefinition(): FfiRegionDefinition =
  when (this) {
    is OfflinePackDefinition.TilePyramid ->
      FfiRegionDefinition(
        OfflineRegionDefinitionData.TilePyramid(
          OfflineTilePyramidRegionDefinition(
            styleUrl = styleUrl,
            bounds = bounds.toLatLngBounds(),
            minZoom = minZoom,
            maxZoom = maxZoom ?: Double.POSITIVE_INFINITY,
            pixelRatio = pixelRatio,
            includeIdeographs = IncludeIdeographs,
          ),
        ),
      )
    is OfflinePackDefinition.Shape ->
      FfiRegionDefinition(
        OfflineRegionDefinitionData.Geometry(
          OfflineGeometryRegionDefinition(
            styleUrl = styleUrl,
            geometry = shape.toJson().encodeToByteArray(),
            minZoom = minZoom,
            maxZoom = maxZoom ?: Double.POSITIVE_INFINITY,
            pixelRatio = pixelRatio,
            includeIdeographs = IncludeIdeographs,
          ),
        ),
      )
    is UnspecifiedOfflinePackDefinition ->
      error("UnspecifiedOfflinePackDefinition has no instances")
  }

/**
 * The common representation of a region MapLibre already has in its database, or null when the FFI
 * reports it as `Unknown`, which carries no style URL and so cannot become an
 * [OfflinePackDefinition].
 */
internal fun FfiRegionDefinition.toOfflinePackDefinition(logger: MapLog?): OfflinePackDefinition? =
  when (val definition = data) {
    is OfflineRegionDefinitionData.TilePyramid ->
      with(definition.value) {
        OfflinePackDefinition.TilePyramid(
          styleUrl = styleUrl,
          bounds = bounds.toBoundingBox(),
          pixelRatio = pixelRatio,
          minZoom = minZoom,
          // MapLibre stores an unlimited maximum as infinity.
          maxZoom = maxZoom.takeIf { it.isFinite() },
        )
      }
    is OfflineRegionDefinitionData.Geometry ->
      with(definition.value) {
        OfflinePackDefinition.Shape(
          styleUrl = styleUrl,
          shape = geometry.toGeoJsonGeometry(logger),
          pixelRatio = pixelRatio,
          minZoom = minZoom,
          maxZoom = maxZoom.takeIf { it.isFinite() },
        )
      }
    else -> {
      logger?.w { "Ignoring an offline region with an unrecognized definition: $this" }
      null
    }
  }

internal fun OfflineRegionStatus.toDownloadProgress(): DownloadProgress =
  DownloadProgress.Healthy(
    completedResourceCount = completedResourceCount.toLong(),
    completedResourceBytes = completedResourceSize.toLong(),
    completedTileCount = completedTileCount.toLong(),
    completedTileBytes = completedTileSize.toLong(),
    status =
      when {
        complete -> DownloadStatus.Complete
        downloadState == OfflineRegionDownloadState.ACTIVE -> DownloadStatus.Downloading
        downloadState == OfflineRegionDownloadState.INACTIVE -> DownloadStatus.Paused
        // Download states are open value classes rather than enums, so a newer native runtime
        // can report one this build has never seen.
        else -> UnrecognizedDownloadStatus(downloadState.rawValue.toInt())
      },
    isRequiredResourceCountPrecise = requiredResourceCountIsPrecise,
    requiredResourceCount = requiredResourceCount.toLong(),
  )

private fun ByteArray.toGeoJsonGeometry(logger: MapLog?): Geometry = runCatching {
  Geometry.fromJson(decodeToString())
}
  .getOrElse {
    // An unreadable shape has no GeoJSON spelling; an empty collection keeps the pack listed and
    // deletable.
    logger?.w(it) { "Offline region shape has no readable GeoJSON; reporting it as empty" }
    GeometryCollection<Geometry>(emptyList())
  }
