package org.maplibre.compose.style

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal const val FONT_FACES_KEY = "font-faces"

internal const val FONT_URL_SCHEME = "mlc-font"

/**
 * The bytes of one TTF or OTF file. Two files with the same content id are the same file: the id is
 * a 64-bit FNV-1a hash of the bytes plus their length, computed once, so equality never compares
 * the bytes themselves.
 */
internal class FontFile(val bytes: ByteArray) {
  val contentId: String = fontContentId(bytes)

  /** The URL the style document refers to this file by. */
  val url: String = "$FONT_URL_SCHEME://$contentId"

  override fun equals(other: Any?): Boolean =
    other is FontFile && (bytes === other.bytes || contentId == other.contentId)

  override fun hashCode(): Int = contentId.hashCode()

  override fun toString(): String = "FontFile($contentId, ${bytes.size} bytes)"
}

private fun fontContentId(bytes: ByteArray): String {
  var hash = -0x340d631b7bdddcdbL // FNV-1a 64-bit offset basis
  for (byte in bytes) {
    hash = (hash xor (byte.toLong() and 0xFF)) * 0x100000001b3L
  }
  return hash.toULong().toString(16).padStart(16, '0') + "-" + bytes.size.toString(16)
}

/** Whether [url] names a registered font file. */
internal fun isFontUrl(url: String): Boolean = url.startsWith("$FONT_URL_SCHEME://")

/** The `font-faces` object that declares [fonts] on top of [base], the style's own declarations. */
internal fun fontFacesJson(base: JsonObject?, fonts: List<StyleFontDefinition>): JsonObject =
  buildJsonObject {
    base?.forEach { (name, declaration) -> put(name, declaration) }
    fonts.forEach { put(it.name, it.file.url) }
  }

/**
 * Returns [document] with [fonts] declared in its `font-faces` object. A registered name replaces
 * the document's own entry under that name. An empty [fonts], or a document that is not a JSON
 * object, returns [document] unchanged so the engine reports the document's own failure.
 */
internal fun mergeFontFaces(document: String, fonts: List<StyleFontDefinition>): String {
  if (fonts.isEmpty()) return document
  val root =
    runCatching { Json.parseToJsonElement(document) }.getOrNull() as? JsonObject ?: return document
  val merged = fontFacesJson(root[FONT_FACES_KEY] as? JsonObject, fonts)
  return JsonObject(root + (FONT_FACES_KEY to merged)).toString()
}

internal fun JsonObject.fontFaceUrls(): Map<String, String?> = mapValues { (_, declaration) ->
  (declaration as? JsonPrimitive)?.takeIf { it.isString }?.content
}
