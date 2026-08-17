package org.maplibre.compose.sources

import js.objects.unsafeJso
import kotlinx.serialization.json.JsonObject
import org.maplibre.compose.gljs.FeatureIdentifier
import org.maplibre.compose.util.toJsValue
import org.maplibre.compose.util.toJsonElement

internal fun Source.featureIdentifiers(
  featureId: String? = null,
  sourceLayerId: String? = null,
): List<FeatureIdentifier> {
  val sourceId = id
  return jsFeatureIds(featureId).map { id ->
    unsafeJso<FeatureIdentifier>().also { ident ->
      ident.source = sourceId
      if (id != null) ident.id = id
      if (sourceLayerId != null) ident.sourceLayer = sourceLayerId
    }
  }
}

/**
 * GL JS matches feature ids by type. The common API matches as text, so a GeoJSON `id` of `7` is
 * `"7"`. An unquoted GeoJSON `id` arrives as a JS number, and a quoted `"7"` arrives as a string.
 * Integer-looking ids therefore target both forms. A leading-zero id such as `"01"` stays a string,
 * because that is not the decimal form of the number.
 */
private fun jsFeatureIds(featureId: String?): List<Any?> {
  if (featureId == null) return listOf(null)
  val integer = featureId.toLongOrNull()
  if (integer == null || integer.toString() != featureId) return listOf(featureId)
  val number =
    if (integer in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) integer.toInt()
    else integer.toDouble()
  return listOf(featureId, number)
}

internal fun Source.setJsFeatureState(
  featureId: String? = null,
  sourceLayerId: String? = null,
  state: JsonObject,
) {
  val jsState = state.toJsValue<Any>()
  mutate { map ->
    for (ident in featureIdentifiers(featureId, sourceLayerId)) {
      map.setFeatureState(ident, jsState)
    }
  }
}

internal fun Source.jsFeatureState(
  featureId: String,
  sourceLayerId: String? = null,
): JsonObject {
  var merged = JsonObject(emptyMap())
  binding?.withMap { map ->
    for (ident in featureIdentifiers(featureId, sourceLayerId)) {
      val next = map.getFeatureState(ident).toJsonObjectOrEmpty()
      if (next.isNotEmpty()) merged = JsonObject(merged + next)
    }
  }
  return merged
}

internal fun Source.removeJsFeatureState(
  featureId: String? = null,
  sourceLayerId: String? = null,
  stateKey: String? = null,
) {
  mutate { map ->
    for (ident in featureIdentifiers(featureId, sourceLayerId)) {
      if (stateKey == null) map.removeFeatureState(ident)
      else map.removeFeatureState(ident, stateKey)
    }
  }
}

internal fun Any?.toJsonObjectOrEmpty(): JsonObject =
  (toJsonElement() as? JsonObject) ?: JsonObject(emptyMap())
