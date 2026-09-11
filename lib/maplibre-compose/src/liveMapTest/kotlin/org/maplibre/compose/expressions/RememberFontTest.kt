package org.maplibre.compose.expressions

import androidx.compose.runtime.mutableStateOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import org.maplibre.compose.expressions.dsl.rememberFont
import org.maplibre.compose.layers.SymbolLayer
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.GeoJsonOptions
import org.maplibre.compose.sources.GeoJsonSource
import org.maplibre.compose.style.DesiredStyleRevision
import org.maplibre.compose.style.FontFile
import org.maplibre.compose.testing.composeStyle
import org.maplibre.spatialk.geojson.dsl.featureCollectionOf

class RememberFontTest {

  private val source =
    GeoJsonSource("features", GeoJsonData.Features(featureCollectionOf()), GeoJsonOptions())

  @Test
  fun the_fallback_stack_stands_in_until_the_file_is_read() = runTest {
    val file = CompletableDeferred<ByteArray>()
    val revisions = mutableListOf<DesiredStyleRevision>()
    var expectFont = false

    composeStyle(
      thenChange = {
        expectFont = true
        file.complete(byteArrayOf(1, 2, 3))
      },
      onRevision = { revisions += it },
      awaitRevision = { it.fonts.isNotEmpty() == expectFont },
    ) {
      SymbolLayer(
        id = "labels",
        source = source,
        textFont = rememberFont("Body", listOf("Fallback"), source = "file") { file.await() },
      )
    }

    assertTrue(revisions.first().fonts.isEmpty())
    assertEquals(stack("Fallback"), revisions.first().textFont())
    val last = revisions.last()
    assertEquals(listOf("Body"), last.fonts.map { it.name })
    assertEquals(FontFile(byteArrayOf(1, 2, 3)), last.fonts.single().file)
    assertEquals(stack("Body", "Fallback"), last.textFont())
  }

  @Test
  fun a_changed_source_is_pending_again_instead_of_keeping_the_previous_file() = runTest {
    val sourceKey = mutableStateOf("first")
    val second = CompletableDeferred<ByteArray>()
    val revisions = mutableListOf<DesiredStyleRevision>()
    var expectFont = true

    composeStyle(
      thenChange = {
        expectFont = false
        sourceKey.value = "second"
      },
      onRevision = { revisions += it },
      awaitRevision = { it.fonts.isNotEmpty() == expectFont },
    ) {
      SymbolLayer(
        id = "labels",
        source = source,
        textFont =
          rememberFont("Body", emptyList(), source = sourceKey.value) {
            if (sourceKey.value == "first") byteArrayOf(1) else second.await()
          },
      )
    }

    assertEquals(listOf("Body"), revisions.first { it.fonts.isNotEmpty() }.fonts.map { it.name })
    val last = revisions.last()
    assertTrue(last.fonts.isEmpty(), "the previous file stayed registered: ${last.fonts}")
    assertEquals(null, last.textFont())
  }

  private fun DesiredStyleRevision.textFont() =
    (layers.single().definition.value["layout"] as? JsonObject)?.get("text-font")

  private fun stack(vararg names: String) = buildJsonArray {
    add(JsonPrimitive("literal"))
    add(buildJsonArray { names.forEach { add(JsonPrimitive(it)) } })
  }
}
