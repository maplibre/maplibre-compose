package org.maplibre.compose.sources

import js.objects.unsafeJso
import kotlin.js.JsAny
import kotlin.js.toJsNumber
import kotlin.js.toJsString
import kotlinx.serialization.json.JsonObject
import org.maplibre.compose.gljs.FeatureIdentifier
import org.maplibre.compose.util.toJsonElement

/** Every identifier form that could name the same feature; see [jsFeatureIds]. */
internal fun featureIdentifiers(
  sourceId: String,
  sourceLayerId: String?,
  featureId: String?,
): List<FeatureIdentifier> =
  jsFeatureIds(featureId).map { id ->
    unsafeJso<FeatureIdentifier>().also { ident ->
      ident.source = sourceId
      if (id != null) ident.id = id
      if (sourceLayerId != null) ident.sourceLayer = sourceLayerId
    }
  }

/**
 * GL JS matches feature ids by type. The common API matches as text, so a GeoJSON `id` of `7` is
 * `"7"`. An unquoted GeoJSON `id` arrives as a JS number, and a quoted `"7"` arrives as a string.
 * Integer-looking ids therefore target both forms. A leading-zero id such as `"01"` stays a string,
 * because that is not the decimal form of the number.
 */
private fun jsFeatureIds(featureId: String?): List<JsAny?> {
  if (featureId == null) return listOf(null)
  val integer = featureId.toLongOrNull()
  if (integer == null || integer.toString() != featureId) return listOf(featureId.toJsString())
  val number =
    if (integer in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) integer.toInt().toJsNumber()
    else integer.toDouble().toJsNumber()
  return listOf(featureId.toJsString(), number)
}

internal fun JsAny?.toJsonObjectOrEmpty(): JsonObject =
  (toJsonElement() as? JsonObject) ?: JsonObject(emptyMap())
