package org.maplibre.compose.sources

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.maplibre.compose.desktop.HeadlessMapFixture
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.DesktopStyle

/**
 * A base-style source read back out of the map, which is all `getSource` can return for one.
 *
 * A style loaded from JSON or a URL has no Kotlin objects behind it, so every source it declares is
 * rebuilt from what MapLibre reports. The reconstruction is a real object with a real definition —
 * [Source.attributionHtml] answers from it, and adding it to a style hands it straight back to
 * MapLibre — so what goes into that definition has to be style JSON rather than merely a JSON
 * object that reads plausibly.
 */
class UnknownSourceRestoreTest {

  @Test
  fun `a base-style source is reconstructed with the style spec's name for its type`() {
    val fixture = HeadlessMapFixture.create()
    fixture.use {
      it.loadStyle(BaseStyle.Json(VECTOR_STYLE))
      val style = assertNotNull(it.style as? DesktopStyle, "Errors: ${it.errors}")

      val source = assertIs<UnknownSource>(style.getSource(SOURCE_ID))

      // Equality against the whole definition rather than the type alone, so what is *not* there is
      // covered too: the definition is what MapLibre would be handed back, and a key invented here
      // is as wrong as a key missing. MapLibre reports the type as an enum whose default `toString`
      // is `SourceType(nativeValue=1)`, which passes for a definition right up until it is used.
      assertEquals(
        Json.parseToJsonElement("""{"type":"vector","attribution":"$ATTRIBUTION"}"""),
        source.definition,
      )
      assertEquals(ATTRIBUTION, source.attributionHtml)
      assertEquals(emptyList(), it.errors, "the map should report nothing")
    }
  }

  /**
   * Re-adding a base-style source is refused, and says which source and why.
   *
   * It cannot work today and the reason is in the FFI rather than here: MapLibre reports a source's
   * type, volatility, and attribution, and nothing else, so a reconstructed tiled source has no
   * `tiles` and no `url` to fetch from. What is being asserted is that the refusal is legible —
   * native says only "source must have tiles", which names neither the source nor the style — and
   * that it fails rather than quietly installing a source that never loads a tile.
   */
  @Test
  fun `re-adding a base-style source fails with a message that names it`() {
    val fixture = HeadlessMapFixture.create()
    fixture.use {
      it.loadStyle(BaseStyle.Json(VECTOR_STYLE))
      val style = assertNotNull(it.style as? DesktopStyle, "Errors: ${it.errors}")

      val source = assertIs<UnknownSource>(style.getSource(SOURCE_ID))
      style.removeSource(source)

      val error = assertFailsWith<IllegalStateException> { style.addSource(source) }
      assertEquals(
        "Could not add source 'vec' of type 'vector': " +
          "INVALID_ARGUMENT (-1): style source is invalid: source must have tiles",
        error.message,
      )
    }
  }

  @Test
  fun `every source in the base style is reported`() {
    val fixture = HeadlessMapFixture.create()
    fixture.use {
      it.loadStyle(BaseStyle.Json(VECTOR_STYLE))
      val style = assertNotNull(it.style as? DesktopStyle, "Errors: ${it.errors}")

      // By id and type together: listing the sources is what a caller enumerates a style with, and
      // a raster source reported as a vector one is a source nothing can be built over correctly.
      //
      // The third entry is MapLibre's own: every map carries an annotation source whether or not
      // anything uses it, and it has no style-spec type because the style spec has no such source.
      // It is reported rather than hidden — it is genuinely in the style — with no type rather than
      // with an invented one.
      assertEquals(
        mapOf(SOURCE_ID to "vector", RASTER_SOURCE_ID to "raster", ANNOTATION_SOURCE_ID to null),
        style.getSources().associate { source ->
          source.id to
            (assertIs<UnknownSource>(source).definition["type"] as? JsonPrimitive)?.content
        },
      )
    }
  }

  private companion object {
    const val SOURCE_ID = "vec"
    const val RASTER_SOURCE_ID = "sat"

    /** MapLibre's own, present in every map whether or not anything draws an annotation. */
    const val ANNOTATION_SOURCE_ID = "org.maplibre.annotations"
    const val ATTRIBUTION = "&copy; Nobody"

    /**
     * Two sources of different types and no layer over either.
     *
     * Unused on purpose: one test removes a source, and MapLibre will not let go of one a layer
     * still draws from. The hosts do not resolve, so no tile is ever requested.
     */
    val VECTOR_STYLE =
      """
      {
        "version": 8,
        "name": "unknown-source-restore-test",
        "sources": {
          "vec": {
            "type": "vector",
            "tiles": ["https://example.invalid/{z}/{x}/{y}.pbf"],
            "minzoom": 0,
            "maxzoom": 14,
            "attribution": "$ATTRIBUTION"
          },
          "sat": {
            "type": "raster",
            "tiles": ["https://example.invalid/{z}/{x}/{y}.png"],
            "tileSize": 256
          }
        },
        "layers": [
          { "id": "bg", "type": "background", "paint": { "background-color": "#ffffff" } }
        ]
      }
      """
        .trimIndent()
  }
}
