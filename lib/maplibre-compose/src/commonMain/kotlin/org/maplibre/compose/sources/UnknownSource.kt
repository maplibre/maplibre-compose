package org.maplibre.compose.sources

import kotlinx.serialization.json.JsonObject

/**
 * A source that came from the style rather than from the composition, such as a base-style source.
 *
 * Reconstructed from the metadata MapLibre retains on the live source: type, attribution, URL or
 * tile templates, and the other TileJSON fields the style declared.
 *
 * @param definition what MapLibre reports about the source.
 */
public class UnknownSource internal constructor(id: String, internal val definition: JsonObject) :
  Source(id) {

  override fun toJson(): JsonObject = definition
}
