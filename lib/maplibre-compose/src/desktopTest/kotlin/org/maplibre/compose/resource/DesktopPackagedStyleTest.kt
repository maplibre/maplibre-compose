package org.maplibre.compose.resource

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.maplibre.compose.mlnffi.HeadlessMapFixture
import org.maplibre.compose.style.BaseStyle

/** A style loaded by a real map from the URI shapes an application ships one in. */
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
   * A `file:` style never reaches the provider — mbgl routes it to its own local file source — so
   * this covers that source's handling of a percent-encoded non-ASCII path, not the provider's.
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
   * A style with one identifiable layer and no sources, sprites, or glyphs, so the style itself is
   * the only resource the map asks for.
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
