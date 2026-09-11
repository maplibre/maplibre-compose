package org.maplibre.compose.style

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

class FontFacesTest {

  private val body = StyleFontDefinition("Body", FontFile(byteArrayOf(1)))

  @Test
  fun a_registered_name_replaces_the_documents_entry_and_keeps_the_rest() {
    val document =
      """{"version":8,"font-faces":{"Body":"https://fonts.test/body.ttf","Title":[{"url":"x"}]},"layers":[]}"""

    val merged = Json.parseToJsonElement(mergeFontFaces(document, listOf(body))).jsonObject
    val faces = merged.getValue("font-faces").jsonObject

    assertEquals(JsonPrimitive(body.file.url), faces["Body"])
    assertEquals(Json.parseToJsonElement("""[{"url":"x"}]"""), faces["Title"])
    assertEquals(JsonPrimitive(8), merged["version"])
  }

  @Test
  fun a_document_without_font_faces_gains_the_registered_ones() {
    val merged = Json.parseToJsonElement(mergeFontFaces("""{"version":8}""", listOf(body)))
    assertEquals(
      JsonObject(mapOf("Body" to JsonPrimitive(body.file.url))),
      merged.jsonObject["font-faces"],
    )
  }

  @Test
  fun no_registered_fonts_leaves_the_document_untouched() {
    val document = """{"version": 8}"""
    assertSame(document, mergeFontFaces(document, emptyList()))
  }
}
