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
import org.maplibre.compose.expressions.ast.compile
import org.maplibre.compose.expressions.dsl.asBoolean
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.convertToColor
import org.maplibre.compose.expressions.dsl.globalState
import org.maplibre.compose.layers.TestLayer
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
      // Without a ready style, a write does nothing.
      state.setProperty("color", JsonPrimitive("blue"))
      fixture.loadStyle(Style)
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
      fixture.loadStyle(EmptyStyle)
      assertEquals(JsonObject(emptyMap()), state.get())
      oldBinding.postOwner { oldBinding.setGlobalStateProperty("leaked", JsonPrimitive(true)) }
      fixture.loadStyle(Style)
      assertEquals(defaults, state.get())
      fixture.closeSession()
      assertFailsWith<IllegalStateException> { state.get() }
      assertFailsWith<IllegalStateException> { state.resetProperty("color") }
    }
  }

  @Test
  fun state_changes_repaint_and_refilter_without_replacing_the_layer(): MapTestResult = runMapTest {
    createMapFixture().use { fixture ->
      fixture.loadStyle(Style)
      val binding = assertNotNull(fixture.style)
      val source = assertIs<VectorSource>(binding.onOwner { binding.getSource("points") })
      val layer = TestLayer("circle", "circle", source)
      layer.paint("circle-radius", (const(48.dp).compile(ExpressionContext.None)).asLayerProperty())
      layer.paint(
        "circle-color",
        (globalState("color").convertToColor(const(Color.Red)).compile(ExpressionContext.None))
          .asLayerProperty(),
      )
      layer.root(
        "filter",
        (globalState("show").asBoolean(const(true)).compile(ExpressionContext.None))
          .asLayerProperty(),
      )
      binding.install(layer)
      val state = fixture.state.style.globalState
      fixture.pumpUntilPixel("default color", 256, 256, Red)
      state.setProperty("color", JsonPrimitive("blue"))
      fixture.pumpUntilPixel("updated paint", 256, 256, Blue)
      state.setProperty("show", JsonPrimitive(false))
      fixture.pumpUntilPixel("updated filter", 256, 256, Black)
      state.resetProperty("show")
      fixture.pumpUntilPixel("reset filter", 256, 256, Blue)
      state.resetProperty("color")
      fixture.pumpUntilPixel("reset paint", 256, 256, Red)
      assertEquals(emptyList(), fixture.errors.toList())
    }
  }

  private companion object {
    val Red = RgbaPixel(255, 0, 0, 255)
    val Blue = RgbaPixel(0, 0, 255, 255)
    val Black = RgbaPixel(0, 0, 0, 255)
    val EmptyStyle = BaseStyle.Json("""{"version":8,"sources":{},"layers":[]}""")
    val Style =
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
