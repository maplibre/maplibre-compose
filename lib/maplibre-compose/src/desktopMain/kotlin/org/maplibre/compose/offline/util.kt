package org.maplibre.compose.offline

import co.touchlab.kermit.Logger
import org.maplibre.compose.util.toBoundingBox
import org.maplibre.compose.util.toLatLng
import org.maplibre.compose.util.toLatLngBounds
import org.maplibre.compose.util.toPosition
import org.maplibre.nativeffi.geo.Geometry as FfiGeometry
import org.maplibre.nativeffi.geo.LatLng
import org.maplibre.nativeffi.offline.OfflineRegionDefinition as FfiRegionDefinition
import org.maplibre.nativeffi.offline.OfflineRegionDownloadState
import org.maplibre.nativeffi.offline.OfflineRegionStatus
import org.maplibre.nativeffi.resource.ResourceErrorReason
import org.maplibre.spatialk.geojson.Geometry
import org.maplibre.spatialk.geojson.GeometryCollection
import org.maplibre.spatialk.geojson.LineString
import org.maplibre.spatialk.geojson.MultiLineString
import org.maplibre.spatialk.geojson.MultiPoint
import org.maplibre.spatialk.geojson.MultiPolygon
import org.maplibre.spatialk.geojson.Point
import org.maplibre.spatialk.geojson.Polygon
import org.maplibre.spatialk.geojson.Position

/**
 * Whether downloaded packs include CJK glyphs.
 *
 * True on desktop, unlike Android and iOS, because those platforms render ideographs from a local
 * system font and MapLibre Native's desktop map has no local-font option.
 */
private const val INCLUDE_IDEOGRAPHS = true

internal fun OfflinePackDefinition.toFfiRegionDefinition(pixelRatio: Float): FfiRegionDefinition =
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
        geometry = shape.toFfiGeometry(),
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
        minZoom = minZoom.toInt(),
        // MapLibre spells "no maximum" as infinity, which does not survive a conversion to Int.
        maxZoom = maxZoom.takeIf { it.isFinite() }?.toInt(),
      )
    is FfiRegionDefinition.GeometryRegion ->
      OfflinePackDefinition.Shape(
        styleUrl = styleUrl,
        shape = geometry.toGeoJsonGeometry(logger),
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

private fun Geometry.toFfiGeometry(): FfiGeometry =
  when (this) {
    is Point -> FfiGeometry.Point(coordinates.toLatLng())
    is MultiPoint -> FfiGeometry.MultiPoint(coordinates.toLatLngs())
    is LineString -> FfiGeometry.LineString(coordinates.toLatLngs())
    is MultiLineString -> FfiGeometry.MultiLineString(coordinates.map { it.toLatLngs() })
    is Polygon -> FfiGeometry.Polygon(coordinates.map { it.toLatLngs() })
    is MultiPolygon ->
      FfiGeometry.MultiPolygon(coordinates.map { rings -> rings.map { it.toLatLngs() } })
    // No else: the GeoJSON hierarchy is sealed, so a new member should break this build.
    is GeometryCollection<*> -> FfiGeometry.Collection(geometries.map { it.toFfiGeometry() })
  }

private fun FfiGeometry.toGeoJsonGeometry(logger: Logger): Geometry =
  when (this) {
    is FfiGeometry.Point -> Point(coordinate.toPosition())
    is FfiGeometry.MultiPoint -> MultiPoint(coordinates.toPositions())
    is FfiGeometry.LineString -> LineString(coordinates.toPositions())
    is FfiGeometry.MultiLineString -> MultiLineString(lines.map { it.toPositions() })
    is FfiGeometry.Polygon -> Polygon(rings.map { it.toPositions() })
    is FfiGeometry.MultiPolygon ->
      MultiPolygon(polygons.map { rings -> rings.map { it.toPositions() } })
    is FfiGeometry.Collection -> GeometryCollection(geometries.map { it.toGeoJsonGeometry(logger) })
    else -> {
      // Empty and Unknown have no GeoJSON spelling; an empty collection keeps the pack listed and
      // deletable.
      logger.w { "Offline region shape $this has no GeoJSON equivalent; reporting it as empty" }
      GeometryCollection<Geometry>(emptyList())
    }
  }

private fun List<Position>.toLatLngs(): List<LatLng> = map { it.toLatLng() }

private fun List<LatLng>.toPositions(): List<Position> = map { it.toPosition() }
