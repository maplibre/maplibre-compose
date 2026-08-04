package org.maplibre.compose.sources

import kotlinx.serialization.json.JsonObject
import org.maplibre.nativeffi.style.SourceType

/**
 * A source that came from the style rather than from the composition, such as a base-style source.
 *
 * @param definition what MapLibre reports about the source: its `type` and, where the style
 *   declares one, its `attribution`. That is the whole of what it reports, which is what
 *   [attributionHtml] answers from.
 *
 * A base-style source cannot be added to another style, because this is not enough to build one
 * from: MapLibre refuses a tiled source with no `tiles` and no `url`, and it reports neither. The
 * attempt fails with a diagnostic naming the source rather than silently producing an empty one.
 *
 * TODO(maplibre-native-ffi): keep the rest of the definition once the FFI reports it. `SourceInfo`
 *   carries the type, the volatility, and the attribution, and a source's URL, tile list, zoom
 *   range, scheme, and bounds are not reachable through any other call.
 */
public actual class UnknownSource
internal constructor(id: String, internal val definition: JsonObject) : Source(id) {

  override fun toJson(): JsonObject = definition
}

/**
 * The style spec's name for a source type, or null for one the spec cannot spell.
 *
 * MapLibre reports a source's type as an enum, and its `toString` is the data-class-shaped default
 * the FFI generates — `SourceType(nativeValue=1)`, not `vector`. Writing that into a reconstructed
 * source's definition is invisible until the source is re-added to a style, at which point MapLibre
 * rejects a type it has never heard of and the layers over it have no data.
 *
 * Annotation, custom-vector, and unknown sources return null: they are runtime constructs with no
 * style-spec type, and naming them something MapLibre would parse would be worse than omitting the
 * key.
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
