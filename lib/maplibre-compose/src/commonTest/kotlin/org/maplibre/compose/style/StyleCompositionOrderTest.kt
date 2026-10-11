package org.maplibre.compose.style

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.maplibre.compose.layers.Anchor
import org.maplibre.compose.layers.LayerSummary
import org.maplibre.compose.layers.TestLayer
import org.maplibre.compose.sources.RasterTileSource
import org.maplibre.compose.style.internal.StyleValue

class StyleCompositionOrderTest {

  @Test
  fun a_second_apply_does_not_move_already_placed_layers() {
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
      val first = TestLayer("first-layer", "raster", source)
      val second = TestLayer("second-layer", "raster", source)
      val revision =
        StyleSnapshot(
          sources = listOf(source.definition()),
          layers =
            listOf(
              StyleSnapshot.Layer(first.definition(), anchor, null, null),
              StyleSnapshot.Layer(second.definition(), anchor, null, null),
            ),
          images = emptyList(),
        )
      val style =
        RecordingStyleBinding(
          layers =
            listOf(
              TestLayer("water", "background"),
              symbolLayer("water-labels"),
              TestLayer("park", "background"),
              TestLayer("roads", "background"),
              symbolLayer("road-labels"),
            )
        )
      val reconciler = StyleReconciler()

      reconciler.apply(style, revision)
      val afterFirst = style.layerIds()
      assertEquals(
        listOf("first-layer", "second-layer"),
        afterFirst.filter { it == "first-layer" || it == "second-layer" },
        "anchor $anchor",
      )
      reconciler.apply(style, revision)

      assertEquals(afterFirst, style.layerIds(), "anchor $anchor")
    }
  }

  @Test
  fun a_single_above_layer_does_not_move_onto_itself() {
    val source =
      RasterTileSource("composed-source", listOf("https://example.invalid/{z}/{x}/{y}.png"))
    val layer = TestLayer("hillshade", "raster", source)
    val revision =
      StyleSnapshot(
        sources = listOf(source.definition()),
        layers = listOf(StyleSnapshot.Layer(layer.definition(), Anchor.Above("water"), null, null)),
        images = emptyList(),
      )
    val style = RecordingStyleBinding(layers = listOf(TestLayer("water", "background")))
    val reconciler = StyleReconciler()

    reconciler.apply(style, revision)
    reconciler.apply(style, revision)

    assertEquals(listOf("water", "hillshade"), style.layerIds())
  }

  @Test
  fun a_predicate_lands_below_its_lowest_match_and_above_its_highest_match() {
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

  /** A predicate receives immutable layer metadata: its type, source, and source layer. */
  @Test
  fun a_predicate_can_read_a_layers_source_and_source_layer() {
    val base =
      listOf(
        TestLayer("bg", "background"),
        TestLayer("pois", layerJson("pois", "symbol", source = "base", sourceLayer = "poi")),
        TestLayer("roads", layerJson("roads", "symbol", source = "base", sourceLayer = "road")),
      )
    val style = RecordingStyleBinding(layers = base)
    val belowRoads = Anchor.Below {
      it.type == "symbol" && it.source == "base" && it.sourceLayer == "road"
    }

    StyleReconciler().apply(style, revision(background("under") to belowRoads))

    assertEquals(listOf("bg", "pois", "under", "roads"), style.layerIds())
  }

  @Test
  fun a_predicate_with_no_match_lands_at_the_end_of_its_scan() {
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

  /**
   * MapLibre GL JS has no layers of its own, so an empty base style has nothing between the top and
   * the bottom of the stack. The two must stay apart, and a scan with no match must reach its end.
   */
  @Test
  fun top_and_bottom_stay_distinct_on_an_empty_base() {
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

    reconciler.apply(style, revision())
    assertEquals(listOf("back", "over-nothing", "front", "under-nothing"), style.layerIds())
  }

  /** Layers outside the declared base style can sit above it. */
  @Test
  fun bottom_lands_under_a_layer_that_is_not_a_base_layer() {
    val style = RecordingStyleBinding(layers = listOf(TestLayer("engine-owned", "background")))
    val base =
      object : StyleBinding by style {
        override val baseLayers: List<LayerSummary> = emptyList()
      }
    val reconciler = StyleReconciler()
    val revision = revision(background("front") to Anchor.Top, background("back") to Anchor.Bottom)

    reconciler.apply(base, revision)
    assertEquals(listOf("back", "engine-owned", "front"), style.layerIds())

    reconciler.apply(base, revision)
    assertEquals(listOf("back", "engine-owned", "front"), style.layerIds())
  }

  @Test
  fun composition_owned_layers_are_never_matched() {
    val style =
      RecordingStyleBinding(layers = listOf(TestLayer("bg", "background"), symbolLayer("labels")))
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
  fun a_second_apply_with_fresh_predicates_changes_nothing() {
    val backing = RecordingStyleBinding(layers = labelledBase())
    val mutations = mutableListOf<String>()
    val style =
      object : StyleBinding by backing {
        override fun addLayer(layer: StyleValue, beforeLayerId: String): Boolean {
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
    reconciler.apply(style, revisionWithFreshPredicates())

    assertEquals(emptyList(), mutations)
    assertEquals(afterFirst, backing.layerIds())
  }

  private fun labelledBase(): List<TestLayer> =
    listOf(
      TestLayer("bg", "background"),
      symbolLayer("water-labels"),
      TestLayer("water", "background"),
      symbolLayer("road-labels"),
      TestLayer("top", "background"),
    )

  private fun revision(vararg layers: Pair<TestLayer, Anchor>) =
    StyleSnapshot(
      sources = emptyList(),
      layers =
        layers.map { (layer, anchor) ->
          StyleSnapshot.Layer(layer.definition(), anchor, null, null)
        },
      images = emptyList(),
    )

  private fun background(id: String): TestLayer = TestLayer(id, "background")

  private fun symbolLayer(id: String): TestLayer = TestLayer(id, layerJson(id, "symbol"))

  private fun layerJson(
    id: String,
    type: String,
    source: String? = null,
    sourceLayer: String? = null,
  ): JsonObject = buildJsonObject {
    put("id", id)
    put("type", type)
    source?.let { put("source", it) }
    sourceLayer?.let { put("source-layer", it) }
  }
}
