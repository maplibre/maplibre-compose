package org.maplibre.compose.style

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.maplibre.compose.expressions.ast.ExpressionContext
import org.maplibre.compose.expressions.dsl.asBoolean
import org.maplibre.compose.expressions.dsl.asEnum
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.convertToColor
import org.maplibre.compose.expressions.dsl.globalState
import org.maplibre.compose.expressions.value.LineCap
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.layers.LineLayer
import org.maplibre.compose.layers.asLayerProperty
import org.maplibre.compose.sources.VectorSource
import org.maplibre.compose.testing.MapTestResult
import org.maplibre.compose.testing.RgbaPixel
import org.maplibre.compose.testing.createMapFixture
import org.maplibre.compose.testing.pumpUntilPixel
import org.maplibre.compose.testing.runMapTest

class StyleGlobalStateTest {
  @Test
  fun defaults_resets_snapshots_and_reload_follow_the_loaded_style(): MapTestResult = runMapTest {
    createMapFixture().use { fixture ->
      val state = fixture.state.style.globalState
      assertNull(state.get())
      assertFailsWith<IllegalStateException> { state.setProperty("color", JsonPrimitive("blue")) }
      fixture.loadStyle(STYLE)
      val oldBinding = assertNotNull(fixture.style)
      val defaults = assertNotNull(state.get())
      assertEquals(JsonPrimitive("red"), defaults["color"])
      val nested = Json.parseToJsonElement("""{"values":[1,true,"+",null]}""")
      state.setProperty("data", nested)
      state.setProperty("color", JsonPrimitive("blue"))
      assertEquals(nested, state.get()?.get("data"))
      assertEquals(JsonPrimitive("blue"), state.get()?.get("color"))
      assertEquals(JsonPrimitive("red"), defaults["color"])
      state.resetProperty("color")
      assertEquals(JsonPrimitive("red"), state.get()?.get("color"))
      state.setProperty("color", JsonPrimitive("blue"))
      state.setProperty("color", JsonNull)
      state.resetProperty("data")
      assertEquals(JsonPrimitive("red"), state.get()?.get("color"))
      assertEquals(JsonNull, state.get()?.get("data"))

      state.setProperty("color", JsonPrimitive("blue"))
      state.setProperty("runtimeOnly", JsonPrimitive(true))
      fixture.loadStyle(EMPTY_STYLE)
      assertEquals(JsonObject(emptyMap()), state.get())
      assertFailsWith<IllegalStateException> {
        oldBinding.setGlobalStateProperty("leaked", JsonPrimitive(true))
      }
      fixture.loadStyle(STYLE)
      assertEquals(defaults, state.get())
      fixture.closeSession()
      assertNull(state.get())
      assertFailsWith<IllegalStateException> { state.resetProperty("color") }
    }
  }

  @Test
  fun state_changes_repaint_and_refilter_without_replacing_the_layer(): MapTestResult = runMapTest {
    createMapFixture().use { fixture ->
      fixture.loadStyle(STYLE)
      val binding = assertNotNull(fixture.style)
      val source = assertIs<VectorSource>(binding.getSource("points"))
      val layer = CircleLayer("circle", source)
      layer.setCircleRadius((const(48.dp).compile(ExpressionContext.None)).asLayerProperty())
      layer.setCircleColor(
        (globalState("color").convertToColor(const(Color.Red)).compile(ExpressionContext.None))
          .asLayerProperty()
      )
      layer.setFilter(
        (globalState("show").asBoolean(const(true)).compile(ExpressionContext.None))
          .asLayerProperty()
      )
      binding.install(layer)
      val state = fixture.state.style.globalState
      fixture.pumpUntilPixel("default color", 256, 256, RED)
      state.setProperty("color", JsonPrimitive("blue"))
      fixture.pumpUntilPixel("updated paint", 256, 256, BLUE)
      state.setProperty("show", JsonPrimitive(false))
      fixture.pumpUntilPixel("updated filter", 256, 256, BLACK)
      state.resetProperty("show")
      fixture.pumpUntilPixel("reset filter", 256, 256, BLUE)
      state.resetProperty("color")
      fixture.pumpUntilPixel("reset paint", 256, 256, RED)
      assertEquals(emptyList(), fixture.errors.toList())
    }
  }

  @Test
  fun state_changes_rebuild_layout_and_color_ramps(): MapTestResult = runMapTest {
    createMapFixture().use { fixture ->
      fixture.loadStyle(LINE_STYLE)
      val binding = assertNotNull(fixture.style)
      val source = assertIs<VectorSource>(binding.getSource("line"))
      val layer = LineLayer("line", source)
      layer.setLineWidth((const(40.dp).compile(ExpressionContext.None)).asLayerProperty())
      layer.setLineCap(
        (globalState("cap").asEnum<LineCap>().compile(ExpressionContext.None)).asLayerProperty()
      )
      layer.setLineGradient(
        (globalState("color").convertToColor().compile(ExpressionContext.None)).asLayerProperty()
      )
      binding.install(layer)
      val state = fixture.state.style.globalState
      fixture.pumpUntilPixel("default line gradient", 256, 256, RED)
      // At zoom 0 the endpoint at longitude 20 is x=284.4. A round 40px cap covers x=296.
      fixture.pumpUntilPixel("butt cap", 296, 256, BLACK)
      state.setProperty("cap", JsonPrimitive("round"))
      fixture.pumpUntilPixel("rebuilt round cap", 296, 256, RED)
      state.setProperty("color", JsonPrimitive("blue"))
      fixture.pumpUntilPixel("updated color ramp", 256, 256, BLUE)
      state.resetProperty("cap")
      fixture.pumpUntilPixel("reset cap", 296, 256, BLACK)
      state.resetProperty("color")
      fixture.pumpUntilPixel("reset color ramp", 256, 256, RED)
      assertEquals(emptyList(), fixture.errors.toList())
    }
  }

  private companion object {
    val RED = RgbaPixel(255, 0, 0, 255)
    val BLUE = RgbaPixel(0, 0, 255, 255)
    val BLACK = RgbaPixel(0, 0, 0, 255)
    val LINE_STYLE =
      BaseStyle.Json(
        """
        {"version":8,"state":{"color":{"default":"red"},"cap":{"default":"butt"}},
         "transition":{"duration":0,"delay":0},
         "sources":{"line":{"type":"geojson","lineMetrics":true,"data":{"type":"FeatureCollection",
           "features":[{"type":"Feature","properties":{},"geometry":{"type":"LineString","coordinates":[[-20,0],[20,0]]}}]}}},
         "layers":[{"id":"background","type":"background","paint":{"background-color":"black"}}]}
        """
          .trimIndent()
      )
    val EMPTY_STYLE = BaseStyle.Json("""{"version":8,"sources":{},"layers":[]}""")
    val STYLE =
      BaseStyle.Json(
        """
        {"version":8,"state":{"color":{"default":"red"},"show":{"default":true}},
         "transition":{"duration":0,"delay":0},
         "sources":{"points":{"type":"geojson","data":{"type":"FeatureCollection",
           "features":[{"type":"Feature","properties":{},"geometry":{"type":"Point","coordinates":[0,0]}}]}}},
         "layers":[{"id":"background","type":"background","paint":{"background-color":"black"}}]}
        """
          .trimIndent()
      )
  }
}
