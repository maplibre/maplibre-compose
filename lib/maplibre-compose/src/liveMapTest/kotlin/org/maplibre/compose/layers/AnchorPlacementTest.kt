package org.maplibre.compose.layers

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.maplibre.compose.map.StyleLoadState
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.onOwner
import org.maplibre.compose.testing.MapTestResult
import org.maplibre.compose.testing.captureWarnings
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

  /**
   * A predicate's exception reaches the code that applied the style content, which is the map's
   * composition in an app. Nothing of that content is applied, and the style is not failed.
   */
  @Test
  fun a_throwing_predicate_throws_its_exception_and_leaves_the_style_as_it_was(): MapTestResult =
    runMapTest {
      createMapFixture().use { fixture ->
        fixture.loadStyle(baseStyle("base-bottom", "base-top"))
        fixture.declare { Anchor.Below("base-top") { BackgroundLayer("kept", visible = true) } }
        val bug = IllegalStateException("bad predicate")
        captureWarnings { warnings ->
          val thrown =
            assertFailsWith<IllegalStateException> {
              fixture.declare {
                Anchor.Below("base-top") { BackgroundLayer("kept", visible = true) }
                Anchor.Above({ throw bug }) { BackgroundLayer("over", visible = true) }
              }
            }

          assertSame(bug, thrown)
          assertEquals(StyleLoadState.Ready, fixture.state.style.loadState)
          assertEquals(
            listOf("base-bottom", "kept", "base-top"),
            fixture.state.style.layers.map { it.id },
          )
          assertEquals(
            listOf("kept"),
            fixture.state.style.declaredRevision.layers.map { it.definition.id },
          )
          assertTrue(warnings.none { "style content" in it }, "Warnings: $warnings")

          // A failed style stays failed, rather than loading a revision that never applies.
          fixture.state.styleAuthority.markStyleFailed(fixture.session, "earlier failure")
          assertFailsWith<IllegalStateException> {
            fixture.declare { Anchor.Above({ throw bug }) { BackgroundLayer("over") } }
          }
          assertEquals(StyleLoadState.Failed("earlier failure"), fixture.state.style.loadState)
        }
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
