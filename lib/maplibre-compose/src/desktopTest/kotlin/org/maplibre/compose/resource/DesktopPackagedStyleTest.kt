package org.maplibre.compose.resource

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.maplibre.compose.desktop.HeadlessMapFixture
import org.maplibre.compose.style.BaseStyle

/**
 * A style loaded by a real map from the URI shapes an application ships one in.
 *
 * The unit tests read the same files directly; this is the proof that a packaged URI survives
 * everything between the style setter and the reader — MapLibre's own loader declining it, the
 * provider claiming it on a network thread, and the answer arriving from a thread MapLibre never
 * called. A style that loads is a style whose bytes came back, so the assertion is on a layer only
 * the packaged file could have supplied.
 */
class DesktopPackagedStyleTest {

  private val resources = PackagedResourceFixture()

  @AfterTest
  fun cleanUp() {
    resources.close()
  }

  @Test
  fun `a style packaged in a jar loads`() {
    val url =
      resources.jarEntry(
        jarName = "demo app.jar",
        entryPath = "composeResources/thé mé/style.json",
        entries = mapOf("composeResources/thé mé/style.json" to style("packaged-in-jar")),
      )

    assertStyleLoads(url, "packaged-in-jar")
  }

  /**
   * The other half of the division of labour, which is not what it looks like from the outside.
   *
   * A `file:` style never reaches the provider: mbgl routes it to its own local file source before
   * the network stack it hooks, so this passes with the provider handing every URL back and fails
   * only if that local source stops handling an encoded path. Both were measured — a provider that
   * passes everything through still loads this style, and one that answers with no bytes at all
   * still loads it, while either breaks the jar above.
   *
   * Kept because it is the boundary the provider is drawn against, and because a percent-encoded
   * path with a space and characters outside ASCII is exactly what a desktop user's home directory
   * produces.
   */
  @Test
  fun `a style beside the application loads from a path with spaces and no ASCII`() {
    val url = resources.file("style sheets/スタイル/style.json", style("read-from-file"))

    assertStyleLoads(url, "read-from-file")
  }

  private fun assertStyleLoads(url: String, layerId: String) {
    HeadlessMapFixture.create().use { fixture ->
      fixture.loadStyle(BaseStyle.Uri(url))

      assertEquals(emptyList(), fixture.errors, "the map reported errors loading $url")
      val style = assertNotNull(fixture.style, "the style should have reached the callbacks")
      assertTrue(
        style.getLayers().any { it.id == layerId },
        "Expected the packaged style's own layer. Got ${style.getLayers().map { it.id }}",
      )
    }
  }

  /**
   * A style with nothing in it but one identifiable layer.
   *
   * No sources and no sprite or glyph URLs, so the only resource the map asks for is the style
   * itself: anything that fails here failed on the URI under test rather than on something it
   * referenced.
   */
  private fun style(layerId: String) =
    """
    {
      "version": 8,
      "name": "packaged",
      "sources": {},
      "layers": [
        { "id": "$layerId", "type": "background", "paint": { "background-color": "#402060" } }
      ]
    }
    """
      .trimIndent()
}
