package org.maplibre.compose.layers

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.RecordingStyleBinding
import org.maplibre.compose.testing.MapTestResult
import org.maplibre.compose.testing.composeStyle
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

  /** A capturing lambda built outside a composable is a new instance on every read. */
  private fun anchorBelow(type: String): Anchor = Anchor.Below { it.type == type }

  @Test
  fun a_fresh_predicate_on_each_recomposition_keeps_the_layer_node(): MapTestResult = runMapTest {
    var generation by mutableStateOf(0)
    val registrations = mutableListOf<Any?>()

    composeStyle(
      RecordingStyleBinding(),
      thenChange = { generation++ },
      onRevision = { revision -> revision.layers.forEach { registrations += it.registration } },
    ) {
      generation
      Anchor.At(anchorBelow("symbol")) { BackgroundLayer("under", visible = true) }
    }

    assertTrue(registrations.size >= 2, "the change did not republish: $registrations")
    registrations.forEach { assertSame(registrations.first(), it) }
  }
}
