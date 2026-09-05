package org.maplibre.compose.sources

import org.maplibre.nativeffi.style.RasterDemEncoding as FfiRasterDemEncoding
import org.maplibre.nativeffi.style.SourceType
import org.maplibre.nativeffi.style.VectorTileEncoding

/**
 * The style spec name, or null for annotation, custom-vector, or unknown source types.
 * [SourceType.toString] returns the generated FFI representation, such as
 * `SourceType(nativeValue=1)`, instead of the style spec name.
 */
internal fun SourceType.toStyleSpecType(): String? =
  when (this) {
    SourceType.VECTOR -> "vector"
    SourceType.RASTER -> "raster"
    SourceType.RASTER_DEM -> "raster-dem"
    SourceType.GEOJSON -> "geojson"
    SourceType.IMAGE -> "image"
    SourceType.VIDEO -> "video"
    else -> null
  }

internal fun VectorTileEncoding.toStyleSpecEncoding(): String? =
  when (this) {
    VectorTileEncoding.MVT -> "mvt"
    VectorTileEncoding.MLT -> "mlt"
    else -> null
  }

internal fun FfiRasterDemEncoding.toStyleSpecEncoding(): String? =
  when (this) {
    FfiRasterDemEncoding.MAPBOX -> "mapbox"
    FfiRasterDemEncoding.TERRARIUM -> "terrarium"
    else -> null
  }
