package org.maplibre.compose.layers

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.testing.MapTestResult
import org.maplibre.compose.testing.createMapFixture
import org.maplibre.compose.testing.declare
import org.maplibre.compose.testing.runMapTest

class AnchorPlacementTest {

  /**
   * An empty base style leaves each engine with only its own layers: none on MapLibre GL JS, the
   * annotation layer on MapLibre Native. Neither may separate the top from the bottom, or be
   * offered to a predicate.
   */
  @Test
  fun anchors_resolve_against_base_layers_only_on_an_empty_base(): MapTestResult = runMapTest {
    createMapFixture().use { fixture ->
      fixture.loadStyle(BaseStyle.Empty)
      fixture.declare {
        BackgroundLayer("front", visible = true)
        Anchor.Bottom { BackgroundLayer("back", visible = true) }
        Anchor.Above({ it.type == "symbol" }) { BackgroundLayer("over-symbols", visible = true) }
      }

      val declared = setOf("front", "back", "over-symbols")
      assertEquals(
        listOf("back", "over-symbols", "front"),
        assertNotNull(fixture.style).layerIds().filter { it in declared },
      )
      assertEquals(declared, fixture.state.style.layers.map { it.id }.toSet())
    }
  }

  @Test
  fun anchors_resolve_against_the_base_layers_of_the_loaded_style_after_a_reload(): MapTestResult =
    runMapTest {
      createMapFixture().use { fixture ->
        fixture.loadStyle(baseStyle("first-bottom", "first-top"))
        fixture.declare {
          Anchor.Below("first-top") { BackgroundLayer("under", visible = true) }
        }
        assertEquals(
          listOf("first-bottom", "under", "first-top"),
          fixture.state.style.layers.map { it.id },
        )

        fixture.loadStyle(baseStyle("second-bottom", "second-top"))
        fixture.declare {
          Anchor.Below("second-top") { BackgroundLayer("under", visible = true) }
          Anchor.Above("first-top") { BackgroundLayer("over-unloaded", visible = true) }
        }

        val expected = listOf("over-unloaded", "second-bottom", "under", "second-top")
        assertEquals(
          expected,
          assertNotNull(fixture.style).layerIds().filter { it in expected },
        )
        assertEquals(expected, fixture.state.style.layers.map { it.id })
      }
    }

  private fun baseStyle(vararg layerIds: String) =
    BaseStyle.Json(
      """
      {
        "version": 8,
        "sources": {},
        "layers": [${layerIds.joinToString { """{ "id": "$it", "type": "background" }""" }}]
      }
      """
        .trimIndent()
    )
}
