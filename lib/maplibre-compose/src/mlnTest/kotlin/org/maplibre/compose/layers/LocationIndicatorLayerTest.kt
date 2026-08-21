package org.maplibre.compose.layers

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import org.maplibre.compose.expressions.ast.ExpressionContext
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.image
import org.maplibre.compose.mlnffi.BridgeMapFixture
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.MlnFfiStyle
import org.maplibre.compose.util.onMap
import org.maplibre.compose.util.toJsonElement
import org.maplibre.spatialk.geojson.Position

class LocationIndicatorLayerTest {

  @Test
  fun location_indicator_properties_reach_maplibre() {
    val fixture = BridgeMapFixture.create()
    fixture.use {
      it.loadStyle(BaseStyle.Empty)
      val style = assertNotNull(it.style as? MlnFfiStyle, "Errors: ${it.errors}")

      val layer = LocationIndicatorLayer("indicator")
      layer.setTopImage(image("top-icon").compile(ExpressionContext.None))
      layer.setBearingImage(image("bearing-icon").compile(ExpressionContext.None))
      layer.setShadowImage(image("shadow-icon").compile(ExpressionContext.None))
      layer.setLocation(Position(longitude = 11.0, latitude = 48.0))
      layer.setBearing(const(45f).compile(ExpressionContext.None))
      layer.setAccuracyRadius(const(20f).compile(ExpressionContext.None))
      layer.setTopImageSize(const(0.5f).compile(ExpressionContext.None))
      layer.setBearingImageSize(const(0.25f).compile(ExpressionContext.None))
      layer.setShadowImageSize(const(0.75f).compile(ExpressionContext.None))
      layer.setImageTiltDisplacement(const(4f).compile(ExpressionContext.None))
      layer.setPerspectiveCompensation(const(0.9f).compile(ExpressionContext.None))
      style.addLayer(layer)

      // The renderer evaluates the layer only when a frame is drawn, and image properties that
      // arrive as expressions abort it there rather than at addLayer.
      it.pumpUntilRendered()
      repeat(3) { _ -> it.frame() }

      layer.onMap { map ->
        assertTrue(map.styleLayerExists("indicator"), "the layer should have been added")
        // A constant image reads back as an object naming it; an expression would read back as an
        // ["image", ...] array, which the renderer cannot take.
        assertEquals(
          JsonPrimitive("top-icon"),
          (map.layerProperty("indicator", "top-image")?.toJsonElement() as? JsonObject)?.get(
            "name"
          ),
          "the image should be written as a plain name, which the renderer reads as a constant",
        )
        assertEquals(
          JsonArray(listOf(JsonPrimitive(48.0), JsonPrimitive(11.0), JsonPrimitive(0.0))),
          map.layerProperty("indicator", "location")?.toJsonElement(),
          "the location should read back as [latitude, longitude, altitude]",
        )
        assertEquals(
          45.0,
          (map.layerProperty("indicator", "bearing")?.toJsonElement() as? JsonPrimitive)
            ?.doubleOrNull,
        )
        assertEquals(
          20.0,
          (map.layerProperty("indicator", "accuracy-radius")?.toJsonElement() as? JsonPrimitive)
            ?.doubleOrNull,
        )
      }

      // A property set on the live layer takes effect too.
      layer.setLocation(Position(longitude = -122.0, latitude = 37.0, altitude = 10.0))
      layer.onMap { map ->
        assertEquals(
          JsonArray(listOf(JsonPrimitive(37.0), JsonPrimitive(-122.0), JsonPrimitive(10.0))),
          map.layerProperty("indicator", "location")?.toJsonElement(),
        )
      }

      assertEquals(emptyList(), it.errors, "the map should report nothing")
    }
  }
}
