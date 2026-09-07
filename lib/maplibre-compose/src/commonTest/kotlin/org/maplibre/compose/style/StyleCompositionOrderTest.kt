package org.maplibre.compose.style

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.maplibre.compose.layers.Anchor
import org.maplibre.compose.layers.BackgroundLayer
import org.maplibre.compose.layers.Layer
import org.maplibre.compose.layers.RasterLayer
import org.maplibre.compose.layers.UnknownLayer
import org.maplibre.compose.sources.RasterTileSource

class StyleCompositionOrderTest {

  @Test
  fun a_complete_revision_preserves_explicit_layer_order() = runTest {
    val source =
      RasterTileSource("composed-source", listOf("https://example.invalid/{z}/{x}/{y}.png"))
    val first = RasterLayer("first-layer", source)
    val second = RasterLayer("second-layer", source)
    val revision =
      DesiredStyleRevision(
        sources = listOf(source.definition()),
        layers =
          listOf(
            DesiredStyleLayer(first.definition(), Anchor.Top, null, null),
            DesiredStyleLayer(second.definition(), Anchor.Top, null, null),
          ),
        images = emptyList(),
      )
    val style = RecordingStyleBinding()

    StyleReconciler().apply(style, revision)

    assertEquals(listOf("first-layer", "second-layer"), style.layerIds())
  }

  @Test
  fun a_second_apply_does_not_move_already_placed_layers() = runTest {
    val anchors =
      listOf(
        Anchor.Top,
        Anchor.Bottom,
        Anchor.Above("water"),
        Anchor.Below("roads"),
        Anchor.Above { it.type == "symbol" },
        Anchor.Below { it.type == "symbol" },
        Anchor.Below("missing"),
        Anchor.Above("missing"),
      )
    for (anchor in anchors) {
      val source =
        RasterTileSource("composed-source", listOf("https://example.invalid/{z}/{x}/{y}.png"))
      val first = RasterLayer("first-layer", source)
      val second = RasterLayer("second-layer", source)
      val revision =
        DesiredStyleRevision(
          sources = listOf(source.definition()),
          layers =
            listOf(
              DesiredStyleLayer(first.definition(), anchor, null, null),
              DesiredStyleLayer(second.definition(), anchor, null, null),
            ),
          images = emptyList(),
        )
      val style =
        RecordingStyleBinding(
          layers =
            listOf(
              BackgroundLayer("water"),
              symbolLayer("water-labels"),
              BackgroundLayer("park"),
              BackgroundLayer("roads"),
              symbolLayer("road-labels"),
            )
        )
      val reconciler = StyleReconciler()

      reconciler.apply(style, revision)
      val afterFirst = style.layerIds()
      reconciler.apply(style, revision)

      assertEquals(afterFirst, style.layerIds(), "anchor $anchor")
    }
  }

  @Test
  fun a_single_above_layer_does_not_move_onto_itself() = runTest {
    val source =
      RasterTileSource("composed-source", listOf("https://example.invalid/{z}/{x}/{y}.png"))
    val layer = RasterLayer("hillshade", source)
    val revision =
      DesiredStyleRevision(
        sources = listOf(source.definition()),
        layers = listOf(DesiredStyleLayer(layer.definition(), Anchor.Above("water"), null, null)),
        images = emptyList(),
      )
    val style = RecordingStyleBinding(layers = listOf(BackgroundLayer("water")))
    val reconciler = StyleReconciler()

    reconciler.apply(style, revision)
    reconciler.apply(style, revision)

    assertEquals(listOf("water", "hillshade"), style.layerIds())
  }

  @Test
  fun a_predicate_lands_below_its_lowest_match_and_above_its_highest_match() = runTest {
    val style = RecordingStyleBinding(layers = labelledBase())
    val below = Anchor.Below { it.type == "symbol" }
    val above = Anchor.Above { it.type == "symbol" }

    StyleReconciler()
      .apply(style, revision(background("under") to below, background("over") to above))

    assertEquals(
      listOf("bg", "under", "water-labels", "water", "road-labels", "over", "top"),
      style.layerIds(),
    )
  }

  @Test
  fun a_predicate_can_read_a_layer_property() = runTest {
    val base =
      listOf(
        BackgroundLayer("bg"),
        UnknownLayer("hidden", layerJson("hidden", "symbol", visibility = "none")),
        symbolLayer("shown"),
      )
    val style = RecordingStyleBinding(layers = base)
    val belowShown = Anchor.Below {
      it.type == "symbol" && it.getProperty("visibility")?.jsonPrimitive?.content != "none"
    }

    StyleReconciler().apply(style, revision(background("under") to belowShown))

    assertEquals(listOf("bg", "hidden", "under", "shown"), style.layerIds())
  }

  @Test
  fun a_predicate_with_no_match_lands_at_the_end_of_its_scan() = runTest {
    val style = RecordingStyleBinding(layers = labelledBase())
    val below = Anchor.Below { it.type == "hillshade" }
    val above = Anchor.Above { it.type == "hillshade" }

    StyleReconciler()
      .apply(style, revision(background("under") to below, background("over") to above))

    assertEquals(
      listOf("over", "bg", "water-labels", "water", "road-labels", "top", "under"),
      style.layerIds(),
    )
  }

  @Test
  fun a_missing_layer_id_lands_at_the_end_of_its_scan() = runTest {
    val style = RecordingStyleBinding(layers = labelledBase())

    StyleReconciler()
      .apply(
        style,
        revision(
          background("under") to Anchor.Below("missing"),
          background("over") to Anchor.Above("missing"),
        ),
      )

    assertEquals(
      listOf("over", "bg", "water-labels", "water", "road-labels", "top", "under"),
      style.layerIds(),
    )
  }

  /**
   * MapLibre GL JS has no layers of its own, so an empty base style has nothing between the top and
   * the bottom of the stack. The two must stay apart, and a scan with no match must reach its end.
   */
  @Test
  fun top_and_bottom_stay_distinct_on_an_empty_base() = runTest {
    val style = RecordingStyleBinding()
    val reconciler = StyleReconciler()
    fun revision() =
      revision(
        background("front") to Anchor.Top,
        background("back") to Anchor.Bottom,
        background("over-nothing") to Anchor.Above { it.type == "symbol" },
        background("under-nothing") to Anchor.Below { it.type == "symbol" },
      )

    reconciler.apply(style, revision())
    assertEquals(listOf("back", "over-nothing", "front", "under-nothing"), style.layerIds())

    val changes = reconciler.apply(style, revision())
    assertEquals(listOf("back", "over-nothing", "front", "under-nothing"), style.layerIds())
    assertNull(changes.layerOrder)
  }

  /** The engine's own layers, such as MapLibre Native's annotation layer, sit above the base. */
  @Test
  fun bottom_lands_under_a_layer_that_is_not_a_base_layer() = runTest {
    val style = RecordingStyleBinding(layers = listOf(BackgroundLayer("engine-owned")))
    val base =
      object : StyleBinding by style {
        override fun layerTypes(): Map<String, String> = emptyMap()
      }
    val reconciler = StyleReconciler()
    val revision = revision(background("front") to Anchor.Top, background("back") to Anchor.Bottom)

    reconciler.apply(base, revision)
    assertEquals(listOf("back", "engine-owned", "front"), style.layerIds())

    val changes = reconciler.apply(base, revision)
    assertEquals(listOf("back", "engine-owned", "front"), style.layerIds())
    assertNull(changes.layerOrder)
  }

  @Test
  fun composition_owned_layers_are_never_matched() = runTest {
    val style = RecordingStyleBinding(layers = listOf(BackgroundLayer("bg"), symbolLayer("labels")))
    val reconciler = StyleReconciler()
    reconciler.apply(style, revision(symbolLayer("composed-labels") to Anchor.Top))

    reconciler.apply(
      style,
      revision(
        symbolLayer("composed-labels") to Anchor.Top,
        background("by-id") to Anchor.Above("composed-labels"),
        background("by-type") to Anchor.Above { it.type == "symbol" },
        background("by-earlier-type") to Anchor.Above { it.type == "background" && it.id != "bg" },
      ),
    )

    assertEquals(
      listOf("by-id", "by-earlier-type", "bg", "labels", "composed-labels", "by-type"),
      style.layerIds(),
    )
  }

  @Test
  fun a_second_apply_with_fresh_predicates_changes_nothing() = runTest {
    val backing = RecordingStyleBinding(layers = labelledBase())
    val mutations = mutableListOf<String>()
    val style =
      object : StyleBinding by backing {
        override fun addLayer(layer: JsonObject, beforeLayerId: String): Boolean {
          mutations += "add"
          return backing.addLayer(layer, beforeLayerId)
        }

        override fun removeLayer(layerId: String) {
          mutations += "remove"
          backing.removeLayer(layerId)
        }

        override fun moveLayer(layerId: String, beforeLayerId: String) {
          mutations += "move"
          backing.moveLayer(layerId, beforeLayerId)
        }
      }
    val reconciler = StyleReconciler()
    fun revisionWithFreshPredicates() =
      revision(
        background("under") to Anchor.Below { it.type == "symbol" },
        background("over") to Anchor.Above { it.type == "symbol" },
        background("also-under") to Anchor.Below { it.type == "symbol" },
        background("nowhere") to Anchor.Above { it.type == "hillshade" },
      )

    reconciler.apply(style, revisionWithFreshPredicates())
    val afterFirst = backing.layerIds()
    mutations.clear()
    val changes = reconciler.apply(style, revisionWithFreshPredicates())

    assertEquals(emptyList(), mutations)
    assertEquals(afterFirst, backing.layerIds())
    assertNull(changes.layerOrder)
  }

  private fun labelledBase(): List<Layer> =
    listOf(
      BackgroundLayer("bg"),
      symbolLayer("water-labels"),
      BackgroundLayer("water"),
      symbolLayer("road-labels"),
      BackgroundLayer("top"),
    )

  private fun revision(vararg layers: Pair<Layer, Anchor>) =
    DesiredStyleRevision(
      sources = emptyList(),
      layers =
        layers.map { (layer, anchor) -> DesiredStyleLayer(layer.definition(), anchor, null, null) },
      images = emptyList(),
    )

  private fun background(id: String): Layer = BackgroundLayer(id)

  private fun symbolLayer(id: String): Layer = UnknownLayer(id, layerJson(id, "symbol"))

  private fun layerJson(id: String, type: String, visibility: String? = null): JsonObject =
    buildJsonObject {
      put("id", id)
      put("type", type)
      visibility?.let { put("layout", buildJsonObject { put("visibility", it) }) }
    }
}
