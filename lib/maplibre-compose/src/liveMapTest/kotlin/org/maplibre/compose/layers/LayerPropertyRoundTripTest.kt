package org.maplibre.compose.layers

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import org.maplibre.compose.expressions.ast.Expression
import org.maplibre.compose.expressions.ast.ExpressionContext
import org.maplibre.compose.expressions.ast.compile
import org.maplibre.compose.expressions.dsl.Feature
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.format
import org.maplibre.compose.expressions.dsl.image
import org.maplibre.compose.expressions.dsl.interpolate
import org.maplibre.compose.expressions.dsl.linear
import org.maplibre.compose.expressions.dsl.span
import org.maplibre.compose.expressions.dsl.textOffset
import org.maplibre.compose.expressions.dsl.textVariableAnchorOffset
import org.maplibre.compose.expressions.dsl.zoom
import org.maplibre.compose.expressions.value.ExpressionValue
import org.maplibre.compose.expressions.value.FloatValue
import org.maplibre.compose.expressions.value.SymbolAnchor
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.GeoJsonSource
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.LayerInstallation
import org.maplibre.compose.style.StyleBinding
import org.maplibre.compose.style.TransitionOptions
import org.maplibre.compose.style.install
import org.maplibre.compose.style.onOwner
import org.maplibre.compose.style.systemAnimatorDurationScale
import org.maplibre.compose.testing.MapLibreFlavor
import org.maplibre.compose.testing.MapTestResult
import org.maplibre.compose.testing.createMapFixture
import org.maplibre.compose.testing.mapLibreFlavor
import org.maplibre.compose.testing.runMapTest
import org.maplibre.compose.util.DpPadding
import org.maplibre.spatialk.geojson.dsl.featureCollectionOf

/** Representative values crossing the JSON insertion and live-property engine APIs. */
class LayerPropertyRoundTripTest {
  @Test
  fun property_values_survive_layer_insertion_and_live_updates(): MapTestResult = runMapTest {
    createMapFixture().use { fixture ->
      fixture.loadStyle(BaseStyle.Empty)
      val style = assertNotNull(fixture.style)
      val source =
        GeoJsonSource(
          "features",
          GeoJsonData.Features(featureCollectionOf()),
        )
      style.install(source)
      for ((index, case) in Cases.withIndex()) {
        val before = TestLayer("before-$index", "symbol", source)
        case.apply(before)
        style.install(before)
        assertProperty(style, before.id, case)

        val after = TestLayer("after-$index", "symbol", source)
        val installation = style.install(after)
        case.apply(after)
        style.onOwner { installation.update(after.definition()) }
        assertProperty(style, after.id, case)
      }
      assertEquals(emptyList(), fixture.errors)
    }
  }

  @Test
  fun zoom_bounds_update_independently_and_omitted_bounds_reset(): MapTestResult = runMapTest {
    createMapFixture().use { fixture ->
      fixture.loadStyle(BaseStyle.Empty)
      val style = assertNotNull(fixture.style)
      val layer =
        TestLayer("background", "background").apply {
          minZoom = 3f
          maxZoom = 15f
        }
      val installation = style.install(layer)
      layer.minZoom = 4f
      style.onOwner {
        installation.update(layer.definition())
        assertTrue(style.layerProperty(layer.id, "minzoom")!!.equivalentTo(JsonPrimitive(4)))
        assertTrue(style.layerProperty(layer.id, "maxzoom")!!.equivalentTo(JsonPrimitive(15)))
      }
      layer.maxZoom = 16f
      style.onOwner {
        installation.update(layer.definition())
        assertTrue(style.layerProperty(layer.id, "minzoom")!!.equivalentTo(JsonPrimitive(4)))
        assertTrue(style.layerProperty(layer.id, "maxzoom")!!.equivalentTo(JsonPrimitive(16)))
      }

      layer.root("minzoom", JsonNull)
      layer.root("maxzoom", JsonNull)
      style.onOwner {
        installation.update(layer.definition())
        assertTrue(style.layerProperty(layer.id, "minzoom")!!.equivalentTo(JsonPrimitive(0)))
        assertTrue(style.layerProperty(layer.id, "maxzoom")!!.equivalentTo(JsonPrimitive(24)))
      }
      assertEquals(emptyList(), fixture.errors)
    }
  }

  @Test
  fun filters_update_and_clear_through_the_filter_api(): MapTestResult = runMapTest {
    createMapFixture().use { fixture ->
      fixture.loadStyle(BaseStyle.Empty)
      val style = assertNotNull(fixture.style)
      val source =
        GeoJsonSource(
          "features",
          GeoJsonData.Features(featureCollectionOf()),
        )
      style.install(source)
      val layer = TestLayer("filtered", "circle", source)
      val original = Json.parseToJsonElement("""["==",["get","class"],"park"]""")
      layer.root("filter", original)
      val installation = style.install(layer)
      assertEquals(original, style.awaitOwner { style.layerProperty(layer.id, "filter") })
      val updated = Json.parseToJsonElement("""["==",["get","class"],"wood"]""")
      layer.root("filter", updated)
      style.onOwner { installation.update(layer.definition()) }
      assertEquals(updated, style.awaitOwner { style.layerProperty(layer.id, "filter") })
      layer.root("filter", JsonNull)
      style.onOwner { installation.update(layer.definition()) }
      val cleared = style.awaitOwner { style.layerProperty(layer.id, "filter") }
      // Native represents the default as an always-true filter; GL JS removes the expression.
      if (mapLibreFlavor == MapLibreFlavor.GlJs) {
        assertTrue(cleared == null || cleared == JsonNull, "cleared filter: $cleared")
      } else {
        assertEquals(JsonPrimitive(true), cleared)
      }
      assertEquals(emptyList(), fixture.errors)
    }
  }

  @Test
  fun transitions_install_update_and_return_to_the_global_timing(): MapTestResult = runMapTest {
    createMapFixture().use { fixture ->
      fixture.loadStyle(BaseStyle.Empty)
      val style = assertNotNull(fixture.style)
      val layer = TestLayer("timed", "background")
      layer.paintTransition(
        "background-color",
        TransitionOptions(700.milliseconds, 50.milliseconds),
      )
      val scale = systemAnimatorDurationScale()
      val installation = style.onOwner { LayerInstallation(style, layer.definition(), "", scale) }
      suspend fun assertTiming(duration: Double, delay: Double) = style.onOwner {
        val written = assertNotNull(style.layerProperty(layer.id, "background-color-transition"))
        val expected =
          Json.parseToJsonElement("""{"duration":${duration * scale},"delay":${delay * scale}}""")
        assertTrue(written.equivalentTo(expected), "expected $expected, got $written")
      }
      assertTiming(700.0, 50.0)
      layer.paintTransition("background-color", TransitionOptions(200.milliseconds))
      style.onOwner { installation.update(layer.definition(), scale) }
      assertTiming(200.0, 0.0)
      layer.paintTransition("background-color", null)
      style.onOwner { installation.update(layer.definition(), scale) }
      val cleared = style.awaitOwner {
        style.layerProperty(layer.id, "background-color-transition")
      }
      // Native reports no value; GL JS reports the empty object used to clear the transition.
      assertTrue(
        cleared == null || cleared == JsonObject(emptyMap()),
        "cleared transition: $cleared",
      )
      assertEquals(emptyList(), fixture.errors)
    }
  }

  private suspend fun assertProperty(style: StyleBinding, id: String, case: Case) = style.onOwner {
    val actual = assertNotNull(style.layerProperty(id, case.property), "$id ${case.property}")
    val expected = Json.parseToJsonElement(case.expectedHere)
    assertTrue(
      actual.equivalentTo(expected),
      "$id ${case.property}: expected $expected, got $actual",
    )
  }

  /** Allows numeric round-off while retaining the engines' known structural differences. */
  private fun JsonElement.equivalentTo(expected: JsonElement): Boolean =
    when {
      this is JsonPrimitive && expected is JsonPrimitive -> {
        val actualNumber = doubleOrNull
        val expectedNumber = expected.doubleOrNull
        if (!isString && !expected.isString && actualNumber != null && expectedNumber != null) {
          abs(actualNumber - expectedNumber) <= 1e-5
        } else this == expected
      }
      this is JsonArray && expected is JsonArray ->
        size == expected.size &&
          zip(expected).all { (actual, wanted) -> actual.equivalentTo(wanted) }
      this is JsonObject && expected is JsonObject ->
        keys == expected.keys &&
          all { (key, actual) -> actual.equivalentTo(expected.getValue(key)) }
      else -> this == expected
    }

  private class Case(
    val property: String,
    val expected: String,
    val glJs: String? = null,
    val apply: (TestLayer) -> Unit,
  ) {
    val expectedHere: String
      get() = if (mapLibreFlavor == MapLibreFlavor.GlJs) glJs ?: expected else expected
  }

  private companion object {
    fun <T : ExpressionValue?> Expression<T>.c() = compile(ExpressionContext.None)

    val Cases =
      listOf<Case>(
        Case("symbol-spacing", "30.0") {
          it.layout("symbol-spacing", const(30.dp).c().asLayerProperty())
        },
        Case("symbol-sort-key", """["number",["get","rank"]]""", """["get","rank"]""") {
          it.layout("symbol-sort-key", (Feature["rank"].cast<FloatValue>().c()).asLayerProperty())
        },
        Case("icon-allow-overlap", "true") {
          it.layout("icon-allow-overlap", (const(true).c()).asLayerProperty())
        },
        Case(
          "icon-text-fit-padding",
          "[-2.5,0.1,-7.1,2.5]",
          """["literal",[-2.5,0.1,-7.1,2.5]]""",
        ) {
          it.layout(
            "icon-text-fit-padding",
            (const(DpPadding(2.5.dp, (-2.5).dp, 0.1.dp, (-7.1).dp)).c()).asLayerProperty(),
          )
        },
        Case("icon-image", """["image","marker"]""") {
          it.layout("icon-image", (image("marker").c()).asLayerProperty())
        },
        Case(
          "text-field",
          """{"sections":[{"text":"Hello","fontStack":null,"textColor":null,"scale":null,
             "image":null}]}""",
          """["format","Hello",{}]""",
        ) {
          it.layout("text-field", (format(span("Hello")).c()).asLayerProperty())
        },
        Case(
          "text-variable-anchor-offset",
          """["top",[0.0,1.0],"bottom",[0.0,-2.0]]""",
          """["let","semiliteral_value",["semiliteral",["top",["literal",[0,1]],"bottom",["literal",[0,-2]]]],["var","semiliteral_value"]]""",
        ) {
          it.layout(
            "text-variable-anchor-offset",
            (textVariableAnchorOffset(
                  SymbolAnchor.Top to textOffset(0.sp, 16.sp),
                  SymbolAnchor.Bottom to textOffset(0.em, (-2).em),
                )
                .compile(
                  object : ExpressionContext by ExpressionContext.None {
                    override val spScale = const(0.0625f)
                    override val emScale = const(1f)
                  }
                ))
              .asLayerProperty(),
          )
        },
        Case("text-opacity", """["interpolate",["linear"],["zoom"],0.0,0.0,10.0,1.0]""") {
          it.paint(
            "text-opacity",
            (interpolate(linear(), zoom(), 0f to const(0f), 10f to const(1f))
                .cast<FloatValue>()
                .c())
              .asLayerProperty(),
          )
        },
        Case(
          "text-color",
          """["rgba",17.0,34.0,51.0,0.5]""",
          "\"rgba(17, 34, 51, 0.5019607843137255)\"",
        ) {
          it.paint("text-color", (const(Color(0x80112233)).c()).asLayerProperty())
        },
      )
  }
}
