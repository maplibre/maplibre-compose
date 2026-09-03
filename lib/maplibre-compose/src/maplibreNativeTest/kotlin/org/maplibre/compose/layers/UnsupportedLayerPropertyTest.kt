package org.maplibre.compose.layers

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.maplibre.compose.expressions.ast.ExpressionContext
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.nil
import org.maplibre.compose.expressions.value.StringValue
import org.maplibre.compose.expressions.value.SymbolOverlap
import org.maplibre.compose.expressions.value.TextRotationAlignment
import org.maplibre.compose.logging.MapLogLevel
import org.maplibre.compose.logging.MapLogger
import org.maplibre.compose.logging.MapLogging
import org.maplibre.compose.mlnffi.BridgeMapFixture
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.GeoJsonOptions
import org.maplibre.compose.sources.GeoJsonSource
import org.maplibre.compose.sources.Source
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.MlnFfiStyleBinding
import org.maplibre.compose.style.TransitionOptions
import org.maplibre.compose.style.install
import org.maplibre.compose.testing.RecordingList
import org.maplibre.compose.util.onMap
import org.maplibre.compose.util.toJsonElement
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.Geometry

/**
 * What a layer does with a property MapLibre Native will not take. The two cases fail differently:
 * an unknown property *name* makes the core refuse the whole layer, so the definition must not
 * write it at all; an unknown *value* is refused on its own and must not escape into the Compose
 * applier. `icon-overlap` and `text-rotation-alignment: viewport-glyph` are the respective cases.
 */
class UnsupportedLayerPropertyTest {

  private val previousLogger = MapLogging.logger

  @BeforeTest
  fun captureWarnings() {
    CAPTURED.clear()
    MapLogging.logger = MapLogger { record ->
      if (record.level >= MapLogLevel.Warning) CAPTURED += record.message
      previousLogger?.log(record)
    }
  }

  @AfterTest
  fun restoreLogger() {
    MapLogging.logger = previousLogger
  }

  @Test
  fun an_overlap_property_is_dropped_rather_than_taking_its_layer_down_with_it() {
    val fixture = BridgeMapFixture.create()
    fixture.use {
      it.loadStyle(BaseStyle.Empty)
      val style = assertNotNull(it.style as? MlnFfiStyleBinding, "Errors: ${it.errors}")
      val source = addSource(style)

      val layer = SymbolLayer("labels", source)
      layer.setIconAllowOverlap(const(true).compile(ExpressionContext.None))
      layer.setTextAllowOverlap(const(true).compile(ExpressionContext.None))
      layer.setIconOverlap(const("cooperative").compile(ExpressionContext.None))
      layer.setTextOverlap(const(SymbolOverlap.Always).compile(ExpressionContext.None))
      // The assertion is that this returns at all: a layer object carrying `icon-overlap` is
      // refused wholesale, and installation turns that into a throw.
      val handle = style.install(layer)

      style.onMap { map ->
        assertTrue(map.styleLayerExists("labels"), "the layer should have been added")
        // MapLibre holds no value for a property that was never written, and reports none.
        assertNull(
          map.layerProperty("labels", "icon-overlap"),
          "icon-overlap should not be written",
        )
        assertNull(
          map.layerProperty("labels", "text-overlap"),
          "text-overlap should not be written",
        )
        assertEquals(
          JsonPrimitive(true),
          map.layerProperty("labels", "icon-allow-overlap")?.toJsonElement(),
        )
        assertEquals(
          JsonPrimitive(true),
          map.layerProperty("labels", "text-allow-overlap")?.toJsonElement(),
        )
      }

      assertEquals(
        listOf(
          "Layer 'labels' of type 'symbol' cannot set 'icon-overlap'",
          "Layer 'labels' of type 'symbol' cannot set 'text-overlap'",
        ),
        warnings().map { warning -> warning.substringBefore(": MapLibre") },
      )

      layer.setIconOverlap(const("never").compile(ExpressionContext.None))
      handle.update(layer.definition())
      style.onMap { map ->
        // The read stays inside the block: onMap rejects a null *result* as an unbound layer.
        assertNull(
          map.layerProperty("labels", "icon-overlap"),
          "icon-overlap should still not be written after installation",
        )
      }
      assertEquals(emptyList(), it.errors, "the map should report nothing")
    }
  }

  @Test
  fun an_overlap_property_nobody_asked_for_is_not_reported() {
    val fixture = BridgeMapFixture.create()
    fixture.use {
      it.loadStyle(BaseStyle.Empty)
      val style = assertNotNull(it.style as? MlnFfiStyleBinding, "Errors: ${it.errors}")
      val source = addSource(style)

      // What every SymbolLayer composable does: an optional property nobody set compiles to a null
      // literal and is handed to the setter anyway.
      val layer = SymbolLayer("labels", source)
      layer.setIconOverlap(nil().cast<StringValue>().compile(ExpressionContext.None))
      layer.setTextOverlap(nil().cast<SymbolOverlap>().compile(ExpressionContext.None))
      style.install(layer)

      assertEquals(emptyList(), warnings(), "an unset property should not be reported")
    }
  }

  @Test
  fun a_value_maplibre_rejects_on_a_live_layer_is_reported_rather_than_thrown() {
    val fixture = BridgeMapFixture.create()
    fixture.use {
      it.loadStyle(BaseStyle.Empty)
      val style = assertNotNull(it.style as? MlnFfiStyleBinding, "Errors: ${it.errors}")
      val source = addSource(style)

      val layer = SymbolLayer("labels", source)
      layer.setTextRotationAlignment(
        const(TextRotationAlignment.Map).compile(ExpressionContext.None)
      )
      val handle = style.install(layer)

      // `viewport-glyph` is in the style spec but not in MapLibre Native, which knows only map,
      // viewport, and auto, yet it arrives through the public API as an ordinary enum member.
      layer.setTextRotationAlignment(
        const(TextRotationAlignment.ViewportGlyph).compile(ExpressionContext.None)
      )
      handle.update(layer.definition())

      style.onMap { map ->
        assertEquals(
          JsonPrimitive("map"),
          map.layerProperty("labels", "text-rotation-alignment")?.toJsonElement(),
          "the layer should have kept the value MapLibre accepted",
        )
      }
      assertEquals(
        listOf(
          "Layer 'labels' of type 'symbol' kept its previous 'text-rotation-alignment': " +
            "MapLibre rejected \"viewport-glyph\"."
        ),
        warnings(),
      )
      assertEquals(emptyList(), it.errors, "the map should report nothing")
    }
  }

  @Test
  fun the_transition_of_an_unsupported_property_is_dropped_with_it() {
    val fixture = BridgeMapFixture.create()
    fixture.use {
      it.loadStyle(BaseStyle.Empty)
      val style = assertNotNull(it.style as? MlnFfiStyleBinding, "Errors: ${it.errors}")
      val source = addSource(style)

      val layer = FillLayer("fills", source)
      layer.setFillLayerOpacity(const(0.5f).compile(ExpressionContext.None))
      layer.setFillLayerOpacityTransition(TransitionOptions(500.milliseconds))
      // The assertion is that this returns at all: a layer object carrying either key is refused
      // wholesale, and installation turns that into a throw.
      style.install(layer)

      style.onMap { map ->
        assertTrue(map.styleLayerExists("fills"), "the layer should have been added")
        assertNull(
          map.layerProperty("fills", "fill-layer-opacity"),
          "fill-layer-opacity should not be written",
        )
        assertNull(
          map.layerProperty("fills", "fill-layer-opacity-transition"),
          "fill-layer-opacity-transition should not be written",
        )
      }

      assertEquals(
        listOf(
          "Layer 'fills' of type 'fill' cannot set 'fill-layer-opacity'",
          "Layer 'fills' of type 'fill' cannot set 'fill-layer-opacity-transition'",
        ),
        warnings().map { warning -> warning.substringBefore(": MapLibre") },
      )
      assertEquals(emptyList(), it.errors, "the map should report nothing")
    }
  }

  private fun warnings(): List<String> = CAPTURED.filter { it.startsWith("Layer ") }

  private fun addSource(style: MlnFfiStyleBinding): Source =
    GeoJsonSource(
        id = "features",
        data = GeoJsonData.Features(FeatureCollection<Geometry, JsonObject?>()),
        options = GeoJsonOptions(),
      )
      .also { style.install(it) }

  private companion object {
    /** Warnings the library logged. */
    val CAPTURED = RecordingList<String>()
  }
}
