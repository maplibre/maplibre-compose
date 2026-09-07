package org.maplibre.compose.expressions.ast

import kotlinx.serialization.json.JsonObject
import org.maplibre.compose.expressions.value.GeoJsonValue

/**
 * A [Literal] GeoJSON object for [within][org.maplibre.compose.expressions.dsl.Feature.within] and
 * [distance][org.maplibre.compose.expressions.dsl.Feature.distance].
 *
 * The style spec takes the object as a function argument, not wrapped in `["literal", ...]`.
 */
public data class GeoJsonLiteral private constructor(override val value: JsonObject) :
  CompiledLiteral<GeoJsonValue, JsonObject> {
  override fun visit(block: (Expression<*>) -> Unit): Unit = block(this)

  public companion object {
    public fun of(value: JsonObject): GeoJsonLiteral = GeoJsonLiteral(value)
  }
}
