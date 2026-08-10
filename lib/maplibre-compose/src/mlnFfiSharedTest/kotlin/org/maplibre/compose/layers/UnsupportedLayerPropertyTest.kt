package org.maplibre.compose.layers

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.maplibre.compose.expressions.ast.ExpressionContext
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.nil
import org.maplibre.compose.expressions.value.StringValue
import org.maplibre.compose.expressions.value.SymbolOverlap
import org.maplibre.compose.expressions.value.TextRotationAlignment
import org.maplibre.compose.mlnffi.BridgeMapFixture
import org.maplibre.compose.mlnffi.RecordingList
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.GeoJsonOptions
import org.maplibre.compose.sources.GeoJsonSource
import org.maplibre.compose.sources.Source
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.MlnFfiStyle
import org.maplibre.compose.util.onMap
import org.maplibre.compose.util.toJsonElement
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.Geometry

/**
 * What a layer does with a property MapLibre Native will not take. The two cases fail differently:
 * an unknown property *name* makes the core refuse the whole layer, so the descriptor must not
 * write it at all; an unknown *value* is refused on its own and must not escape into the Compose
 * applier. `icon-overlap` and `text-rotation-alignment: viewport-glyph` are the respective cases.
 */
class UnsupportedLayerPropertyTest {

  @BeforeTest
  fun clearLog() {
    CAPTURED.clear()
  }

  @Test
  fun an_overlap_property_is_dropped_rather_than_taking_its_layer_down_with_it() {
    val fixture = BridgeMapFixture.create()
    fixture.use {
      it.loadStyle(BaseStyle.Empty)
      val style = assertNotNull(it.style as? MlnFfiStyle, "Errors: ${it.errors}")
      val source = addSource(style)

      val layer = SymbolLayer("labels", source)
      layer.setIconAllowOverlap(const(true).compile(ExpressionContext.None))
      layer.setTextAllowOverlap(const(true).compile(ExpressionContext.None))
      layer.setIconOverlap(const("cooperative").compile(ExpressionContext.None))
      layer.setTextOverlap(const(SymbolOverlap.Always).compile(ExpressionContext.None))
      // The assertion is that this returns at all: a layer object carrying `icon-overlap` is
      // refused wholesale, and attach turns that into a throw.
      style.addLayer(layer)

      layer.onMap { map ->
        assertTrue(map.styleLayerExists("labels"), "the layer should have been added")
        // MapLibre answers JSON null for a property it holds no value for, so that is what "was
        // never written" looks like here.
        assertEquals(
          JsonNull,
          map.layerProperty("labels", "icon-overlap")?.toJsonElement(),
          "icon-overlap should not be written",
        )
        assertEquals(
          JsonNull,
          map.layerProperty("labels", "text-overlap")?.toJsonElement(),
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
      assertEquals(
        JsonNull,
        layer.onMap { map -> map.layerProperty("labels", "icon-overlap")?.toJsonElement() },
        "icon-overlap should still not be written after attach",
      )
      assertEquals(emptyList(), it.errors, "the map should report nothing")
    }
  }

  @Test
  fun an_overlap_property_nobody_asked_for_is_not_reported() {
    val fixture = BridgeMapFixture.create()
    fixture.use {
      it.loadStyle(BaseStyle.Empty)
      val style = assertNotNull(it.style as? MlnFfiStyle, "Errors: ${it.errors}")
      val source = addSource(style)

      // What every SymbolLayer composable does: an optional property nobody set compiles to a null
      // literal and is handed to the setter anyway.
      val layer = SymbolLayer("labels", source)
      layer.setIconOverlap(nil().cast<StringValue>().compile(ExpressionContext.None))
      layer.setTextOverlap(nil().cast<SymbolOverlap>().compile(ExpressionContext.None))
      style.addLayer(layer)

      assertEquals(emptyList(), warnings(), "an unset property should not be reported")
    }
  }

  @Test
  fun a_value_maplibre_rejects_on_a_live_layer_is_reported_rather_than_thrown() {
    val fixture = BridgeMapFixture.create()
    fixture.use {
      it.loadStyle(BaseStyle.Empty)
      val style = assertNotNull(it.style as? MlnFfiStyle, "Errors: ${it.errors}")
      val source = addSource(style)

      val layer = SymbolLayer("labels", source)
      layer.setTextRotationAlignment(
        const(TextRotationAlignment.Map).compile(ExpressionContext.None)
      )
      style.addLayer(layer)

      // `viewport-glyph` is in the style spec but not in MapLibre Native, which knows only map,
      // viewport, and auto, yet it arrives through the public API as an ordinary enum member.
      layer.setTextRotationAlignment(
        const(TextRotationAlignment.ViewportGlyph).compile(ExpressionContext.None)
      )

      layer.onMap { map ->
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

  private fun warnings(): List<String> = CAPTURED.filter { it.startsWith("Layer ") }

  private fun addSource(style: MlnFfiStyle): Source =
    GeoJsonSource(
        id = "features",
        data = GeoJsonData.Features(FeatureCollection<Geometry, JsonObject?>()),
        options = GeoJsonOptions(),
      )
      .also { style.addSource(it) }

  private companion object {
    /**
     * Warnings the library logged. Kermit's writers are global and cannot be removed, so this is
     * installed once; each platform test process runs without parallel forks.
     */
    val CAPTURED = RecordingList<String>()

    init {
      Logger.addLogWriter(
        object : LogWriter() {
          override fun log(
            severity: Severity,
            message: String,
            tag: String,
            throwable: Throwable?,
          ) {
            if (severity >= Severity.Warn) CAPTURED += message
          }
        }
      )
    }
  }
}
