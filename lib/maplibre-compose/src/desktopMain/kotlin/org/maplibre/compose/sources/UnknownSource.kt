package org.maplibre.compose.sources

import kotlinx.serialization.json.JsonObject
import org.maplibre.nativeffi.style.SourceType

/**
 * A source that came from the style rather than from the composition, such as a base-style source.
 *
 * @param definition what MapLibre reports about the source: its `type` and, where the style
 *   declares one, its `attribution`.
 *
 * Only the attribution is ever read back, by [attributionHtml]. Nothing re-adds one of these: a
 * base-style source is referenced by id, and `SourceManager.addReference` rejects any source whose
 * id belongs to the base style, so this definition never reaches `addStyleSourceJson`. A thin
 * definition therefore costs nothing.
 *
 * Which is also why the FFI reporting more of one would not change anything here. `SourceInfo` now
 * carries a source's URL, tile list, zoom range, scheme, bounds, tile size, and encoding, and none
 * of it has a consumer: reading a source's configuration is not something MapLibre Compose offers
 * on any platform, and adding it would be a common API decision rather than a desktop one. See
 * COMMON_API_GAPS.md.
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
