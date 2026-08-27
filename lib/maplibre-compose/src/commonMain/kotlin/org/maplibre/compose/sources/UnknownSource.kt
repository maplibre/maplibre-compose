package org.maplibre.compose.sources

import kotlinx.serialization.json.JsonObject

/**
 * A source that came from the style rather than from the composition, such as a base-style source.
 */
public class UnknownSource internal constructor(id: String, internal val definition: JsonObject) :
  Source(id) {

  override fun toJson(): JsonObject = definition
}
