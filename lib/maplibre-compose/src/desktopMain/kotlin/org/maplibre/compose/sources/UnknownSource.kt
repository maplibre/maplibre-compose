package org.maplibre.compose.sources

import kotlinx.serialization.json.JsonObject

/**
 * A source that came from the style rather than from the composition, such as a base-style source.
 *
 * @param definition what MapLibre reports about the source, at minimum its `type` and, where the
 *   style declares one, its `attribution`. Keeping the definition is what lets [attributionHtml]
 *   answer for a base source and lets the source be re-added to a later style.
 */
public actual class UnknownSource
internal constructor(id: String, internal val definition: JsonObject) : Source(id) {

  override fun toJson(): JsonObject = definition
}
