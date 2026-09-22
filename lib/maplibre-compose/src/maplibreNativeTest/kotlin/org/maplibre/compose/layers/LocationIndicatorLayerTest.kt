package org.maplibre.compose.layers

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import org.maplibre.compose.expressions.ast.ConstantImageExpression
import org.maplibre.compose.expressions.ast.ExpressionContext
import org.maplibre.compose.expressions.ast.FunctionCall
import org.maplibre.compose.expressions.ast.PainterLiteral
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.convertToString
import org.maplibre.compose.expressions.dsl.image
import org.maplibre.compose.expressions.value.StringValue
import org.maplibre.compose.mlnffi.BridgeMapFixture
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.MlnFfiStyleBinding
import org.maplibre.compose.style.TransitionOptions
import org.maplibre.compose.style.install
import org.maplibre.compose.util.onMap
import org.maplibre.compose.util.toJsonElement
import org.maplibre.spatialk.geojson.Position

class LocationIndicatorLayerTest {

  @Test
  fun image_declarations_preserve_deferred_resources_and_record_unsupported_values() {
    val managed = ConstantImageExpression(image(ColorPainter(Color.Blue)))
    assertTrue(managed.isSupported)
    var painters = 0
    managed.visit { if (it is PainterLiteral) painters++ }
    assertEquals(1, painters)
    val context =
      object : ExpressionContext by ExpressionContext.None {
        override fun resolvePainter(painter: PainterLiteral): String = "resolved-painter"
      }
    assertEquals(
      JsonPrimitive("resolved-painter"),
      managed.compile(context).asLayerProperty().resolve(emptyMap()),
    )

    val dynamic = image(FunctionCall.of("get", const("icon")).cast<StringValue>())
    assertTrue(!ConstantImageExpression(image(const(12.sp).convertToString())).isSupported)
    val definition =
      testLayerPropertyCache().snapshot {
        locationIndicatorImage("top-image", dynamic)
      }
    assertTrue(definition.images.isEmpty())
    assertTrue("layout" !in definition.definition.value)
    assertEquals(
      mapOf("top-image" to "MapLibre Native reads only a constant image here"),
      definition.definition.unsupportedProperties,
    )
  }

  @Test
  fun location_indicator_properties_reach_maplibre() {
    val fixture = BridgeMapFixture.create()
    fixture.use {
      it.loadStyle(BaseStyle.Empty)
      val style = assertNotNull(it.style as? MlnFfiStyleBinding, "Errors: ${it.errors}")

      val layer = TestLayer("indicator", "location-indicator")
      layer.layout(
        "top-image",
        ConstantImageExpression(image("top-icon"))
          .compile(ExpressionContext.None)
          .asLayerProperty(),
      )
      layer.layout(
        "bearing-image",
        ConstantImageExpression(image("bearing-icon"))
          .compile(ExpressionContext.None)
          .asLayerProperty(),
      )
      layer.layout(
        "shadow-image",
        ConstantImageExpression(image("shadow-icon"))
          .compile(ExpressionContext.None)
          .asLayerProperty(),
      )
      layer.paintTransition("location", TransitionOptions(500.milliseconds))
      layer.paintTransition("bearing", TransitionOptions(500.milliseconds))
      layer.paintTransition("accuracy-radius", TransitionOptions(500.milliseconds))
      layer.paintTransition("bearing-accuracy", TransitionOptions(500.milliseconds))
      layer.paintTransition("bearing-accuracy-radius", TransitionOptions(500.milliseconds))
      layer.paintTransition("bearing-accuracy-color", TransitionOptions(500.milliseconds))
      layer.paint(
        "bearing-accuracy",
        (const(15f).compile(ExpressionContext.None)).asLayerProperty(),
      )
      layer.paint(
        "bearing-accuracy-radius",
        (const(48.dp).compile(ExpressionContext.None)).asLayerProperty(),
      )
      layer.paint(
        "bearing-accuracy-color",
        (const(Color.Blue).compile(ExpressionContext.None)).asLayerProperty(),
      )
      layer.paint(
        "location",
        locationIndicatorPositionJson(Position(longitude = 11.0, latitude = 48.0)),
      )
      layer.paint("bearing", (const(45f).compile(ExpressionContext.None)).asLayerProperty())
      layer.paint("accuracy-radius", (const(20f).compile(ExpressionContext.None)).asLayerProperty())
      layer.paint("top-image-size", (const(0.5f).compile(ExpressionContext.None)).asLayerProperty())
      layer.paint(
        "bearing-image-size",
        (const(0.25f).compile(ExpressionContext.None)).asLayerProperty(),
      )
      layer.paint(
        "shadow-image-size",
        (const(0.75f).compile(ExpressionContext.None)).asLayerProperty(),
      )
      layer.paint(
        "image-tilt-displacement",
        (const(4f).compile(ExpressionContext.None)).asLayerProperty(),
      )
      layer.paint(
        "perspective-compensation",
        (const(0.9f).compile(ExpressionContext.None)).asLayerProperty(),
      )
      val handle = style.install(layer)

      // The renderer evaluates the layer only when a frame is drawn, and image properties that
      // arrive as expressions abort it there rather than at addLayer.
      it.pumpUntilRendered()
      repeat(3) { _ -> it.frame() }

      style.onMap { map ->
        assertTrue(map.styleLayerExists("indicator"), "the layer should have been added")
        for (property in
          listOf(
            "location",
            "bearing",
            "accuracy-radius",
            "bearing-accuracy",
            "bearing-accuracy-radius",
            "bearing-accuracy-color",
          )) {
          val transition =
            map.layerProperty("indicator", "$property-transition")?.toJsonElement() as? JsonObject
          assertEquals(500.0, (transition?.get("duration") as? JsonPrimitive)?.doubleOrNull)
        }
        assertEquals(
          15.0,
          (map.layerProperty("indicator", "bearing-accuracy")?.toJsonElement() as? JsonPrimitive)
            ?.doubleOrNull,
        )
        assertEquals(
          48.0,
          (map.layerProperty("indicator", "bearing-accuracy-radius")?.toJsonElement()
              as? JsonPrimitive)
            ?.doubleOrNull,
        )
        assertNotNull(map.layerProperty("indicator", "bearing-accuracy-color"))
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
      layer.paint(
        "location",
        locationIndicatorPositionJson(
          Position(longitude = -122.0, latitude = 37.0, altitude = 10.0)
        ),
      )
      handle.update(layer.definition())
      style.onMap { map ->
        assertEquals(
          JsonArray(listOf(JsonPrimitive(37.0), JsonPrimitive(-122.0), JsonPrimitive(10.0))),
          map.layerProperty("indicator", "location")?.toJsonElement(),
        )
      }

      assertEquals(emptyList(), it.errors, "the map should report nothing")
    }
  }
}
