package org.maplibre.compose.sources

import kotlinx.serialization.json.JsonObject
import org.maplibre.nativeffi.style.RasterDemEncoding as FfiRasterDemEncoding
import org.maplibre.nativeffi.style.SourceType
import org.maplibre.nativeffi.style.VectorTileEncoding

/**
 * A source that came from the style rather than from the composition, such as a base-style source.
 *
 * Reconstructed from the metadata MapLibre retains on the live source: type, attribution, URL or
 * tile templates, and the other TileJSON fields the style declared.
 *
 * @param definition what MapLibre reports about the source.
 */
public actual class UnknownSource
internal constructor(id: String, internal val definition: JsonObject) : Source(id) {

  override fun toJson(): JsonObject = definition
}

/**
 * The style spec's name for a source type, or null for one the spec cannot spell (annotation,
 * custom-vector, unknown). [SourceType.toString] is the FFI's generated default —
 * `SourceType(nativeValue=1)`, not `vector` — so it cannot be used here.
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
