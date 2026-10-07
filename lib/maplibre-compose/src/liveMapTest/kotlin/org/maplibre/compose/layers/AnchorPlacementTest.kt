package org.maplibre.compose.layers

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.maplibre.compose.map.StyleLoadState
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.onOwner
import org.maplibre.compose.testing.MapTestResult
import org.maplibre.compose.testing.createMapFixture
import org.maplibre.compose.testing.declare
import org.maplibre.compose.testing.runMapTest

class AnchorPlacementTest {

  /**
   * An empty base style offers no layers to anchor predicates. Application layers must not separate
   * the top and bottom anchors.
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
        assertNotNull(fixture.style).let { it.onOwner { it.layerIds() } }.filter { it in declared },
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
          assertNotNull(fixture.style)
            .let { it.onOwner { it.layerIds() } }
            .filter { it in expected },
        )
        assertEquals(expected, fixture.state.style.layers.map { it.id })
      }
    }

  /** A predicate that throws fails the style content like any other failure applying it. */
  @Test
  fun a_throwing_predicate_fails_the_style(): MapTestResult = runMapTest {
    createMapFixture().use { fixture ->
      fixture.loadStyle(baseStyle("base-bottom", "base-top"))
      fixture.declare {
        Anchor.Above({ error("bad predicate") }) { BackgroundLayer("over", visible = true) }
      }

      assertEquals(StyleLoadState.Failed("bad predicate"), fixture.state.style.loadState)
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
