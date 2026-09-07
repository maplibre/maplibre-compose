package org.maplibre.compose.sources

import kotlinx.serialization.json.JsonObject

/**
 * A source whose style-spec `type` this API does not construct.
 *
 * Known types reconstruct as [VectorSource], [RasterSource], [RasterDemSource], [GeoJsonSource],
 * [ImageSource], or [VideoSource]. This class remains the fallback so a later type can still be
 * read, re-added, and looked up by id.
 *
 * @param definition what MapLibre reports about the source.
 */
public class UnknownSource internal constructor(id: String, internal val definition: JsonObject) :
  Source(id) {

  override fun toJson(): JsonObject = definition
}
