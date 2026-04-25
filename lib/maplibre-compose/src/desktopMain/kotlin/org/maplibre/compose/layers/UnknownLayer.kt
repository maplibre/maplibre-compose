package org.maplibre.compose.layers

/**
 * Represents a layer that exists in the base style but was not added by user Compose code. The
 * full JSON spec is cached so the layer can be re-inserted after removal (e.g. for Anchor.Replace).
 */
internal actual class UnknownLayer(
  private val _layerId: String,
  internal val jsonSpec: String,
) : Layer() {
  @Suppress("UNREACHABLE_CODE") override val impl: Nothing get() = TODO()
  override val layerId: String get() = _layerId
  override fun layerType(): String = extractJsonStringField(jsonSpec, "type") ?: "unknown"

  override fun toJson(): String = jsonSpec
}

private fun extractJsonStringField(json: String, field: String): String? {
  val key = "\"$field\""
  val keyIdx = json.indexOf(key)
  if (keyIdx < 0) return null
  val colon = json.indexOf(':', keyIdx + key.length)
  if (colon < 0) return null
  val quote1 = json.indexOf('"', colon + 1)
  if (quote1 < 0) return null
  val quote2 = json.indexOf('"', quote1 + 1)
  if (quote2 < 0) return null
  return json.substring(quote1 + 1, quote2)
}
