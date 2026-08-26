package org.maplibre.compose.sources

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.expressions.ast.ExpressionContext
import org.maplibre.compose.expressions.dsl.all
import org.maplibre.compose.expressions.dsl.asBoolean
import org.maplibre.compose.expressions.dsl.condition
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.feature
import org.maplibre.compose.expressions.dsl.switch
import org.maplibre.compose.expressions.value.BooleanValue
import org.maplibre.compose.layers.CircleLayerDescriptor
import org.maplibre.compose.mlnffi.BridgeMapFixture
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.StyleBinding
import org.maplibre.compose.testing.RgbaPixel
import org.maplibre.compose.util.toJsonElement
import org.maplibre.nativeffi.query.FeatureStateSelector
import org.maplibre.spatialk.geojson.Geometry
import org.maplibre.spatialk.geojson.Point
import org.maplibre.spatialk.geojson.Position
import org.maplibre.spatialk.geojson.dsl.addFeature
import org.maplibre.spatialk.geojson.dsl.buildFeatureCollection

class MlnFfiFeatureStateLifecycleTest {

  @Test
  fun state_written_before_the_first_frame_reaches_the_renderer() {
    BridgeMapFixture.create().use { fixture ->
      fixture.loadStyleBeforeRendering(BLACK_STYLE)
      assertEquals(0, fixture.attachCount)
      fixture.core.setCameraPosition(CameraPosition(target = Position(0.0, 0.0), zoom = 1.0))
      val style = assertNotNull(fixture.style)
      val source = attachPointSource(style)
      attachStateLayer(style, source, "selected")

      source.setFeatureState("1", state("selected"))
      assertTrue(source.getFeatureState("1")["selected"]?.jsonPrimitive?.boolean == true)

      fixture.pumpUntilPixel("the retained state to color the circle", RED)
      assertEquals(1, fixture.attachCount)
    }
  }

  @Test
  fun state_survives_surface_loss_and_accepts_detached_mutations() {
    BridgeMapFixture.create().use { fixture ->
      fixture.loadStyle(BLACK_STYLE)
      fixture.core.setCameraPosition(CameraPosition(target = Position(0.0, 0.0), zoom = 1.0))
      val style = assertNotNull(fixture.style)
      val source = attachPointSource(style)
      attachStateLayer(style, source, "persistent", "detached")
      fixture.pumpUntilPixel("the default circle", BLUE)

      source.setFeatureState("1", state("persistent", "discarded"))
      fixture.loseSurface()
      source.setFeatureState("1", state("detached"))
      source.removeFeatureState("1", "discarded")
      val retained = source.getFeatureState("1")
      assertTrue(retained["persistent"]?.jsonPrimitive?.boolean == true)
      assertTrue(retained["detached"]?.jsonPrimitive?.boolean == true)
      assertFalse("discarded" in retained)

      fixture.restoreSurface()
      fixture.pumpUntilPixel("state from both renderer lifetimes to color the circle", RED)

      fixture.loseSurface()
      source.resetFeatureStates()
      assertEquals(JsonObject(emptyMap()), source.getFeatureState("1"))
      fixture.restoreSurface()
      fixture.pumpUntilPixel("the reset state to restore the default circle", BLUE)
    }
  }

  @Test
  fun vector_layers_replay_independently_after_surface_loss() {
    BridgeMapFixture.create().use { fixture ->
      fixture.loadStyle(BaseStyle.Empty)
      val style = assertNotNull(fixture.style)
      val source =
        VectorSource(
          id = "vector",
          tiles = listOf("https://example.invalid/{z}/{x}/{y}.pbf"),
          options = TileSetOptions(),
        )
      style.addSource(source)
      source.setFeatureState("kept", "1", state("selected"))
      source.setFeatureState("reset", "1", state("selected"))

      fixture.loseSurface()
      source.resetFeatureStates("reset")
      assertTrue(source.getFeatureState("kept", "1")["selected"]?.jsonPrimitive?.boolean == true)
      assertEquals(JsonObject(emptyMap()), source.getFeatureState("reset", "1"))

      fixture.restoreSurface()
      fixture.pumpUntilRendered()
      assertTrue(
        nativeFeatureState(source, "kept", "1")["selected"]?.jsonPrimitive?.boolean == true
      )
      assertEquals(JsonObject(emptyMap()), nativeFeatureState(source, "reset", "1"))
    }
  }

  @Test
  fun removing_a_source_forgets_its_retained_state() {
    BridgeMapFixture.create().use { fixture ->
      fixture.loadStyle(BaseStyle.Empty)
      val style = assertNotNull(fixture.style)
      val source = attachPointSource(style)
      source.setFeatureState("1", state("selected"))

      style.removeSource(source)
      style.addSource(source)

      assertEquals(JsonObject(emptyMap()), source.getFeatureState("1"))
    }
  }

  @Test
  fun replacing_the_style_discards_its_retained_state() {
    BridgeMapFixture.create().use { fixture ->
      fixture.loadStyle(BaseStyle.Empty)
      val first = attachPointSource(assertNotNull(fixture.style))
      first.setFeatureState("1", state("selected"))

      fixture.loadStyle(BLACK_STYLE)
      val replacement = attachPointSource(assertNotNull(fixture.style))

      assertEquals(JsonObject(emptyMap()), first.getFeatureState("1"))
      assertEquals(JsonObject(emptyMap()), replacement.getFeatureState("1"))
    }
  }

  private fun attachPointSource(style: StyleBinding): GeoJsonSource {
    val source =
      GeoJsonSource(
        id = "points",
        data =
          GeoJsonData.Features(
            buildFeatureCollection<Geometry, JsonObject?> {
              addFeature(geometry = Point(Position(0.0, 0.0))) { setId(1) }
            }
          ),
        options = GeoJsonOptions(),
      )
    style.addSource(source)
    return source
  }

  private fun attachStateLayer(
    style: StyleBinding,
    source: GeoJsonSource,
    vararg requiredKeys: String,
  ) {
    val layer = CircleLayerDescriptor("circles", source)
    layer.setCircleRadius(const(48.dp).compile(ExpressionContext.None))
    layer.setCircleColor(
      switch(
          condition(
            test =
              all(
                *requiredKeys
                  .map { key -> feature.state<BooleanValue>(key).asBoolean(const(false)) }
                  .toTypedArray()
              ),
            output = const(Color.Red),
          ),
          fallback = const(Color.Blue),
        )
        .compile(ExpressionContext.None)
    )
    style.addLayer(layer)
  }

  private fun BridgeMapFixture.pumpUntilPixel(description: String, expected: RgbaPixel) {
    pumpUntil(description) { hasRendered && readPixel(CENTER, CENTER).isNear(expected) }
  }

  private fun nativeFeatureState(
    source: VectorSource,
    sourceLayerId: String,
    featureId: String,
  ): JsonObject {
    val bytes =
      assertNotNull(
        source.ffiBinding?.withRenderSession { session ->
          session.getFeatureState(
            FeatureStateSelector(source.id).apply {
              this.sourceLayerId = sourceLayerId
              this.featureId = featureId
            }
          )
        }
      )
    return assertIs<JsonObject>(bytes.toJsonElement())
  }

  private fun state(vararg keys: String): JsonObject = buildJsonObject {
    keys.forEach { key -> put(key, true) }
  }

  private companion object {
    const val CENTER = 256

    val RED = RgbaPixel(red = 255, green = 0, blue = 0, alpha = 255)
    val BLUE = RgbaPixel(red = 0, green = 0, blue = 255, alpha = 255)

    val BLACK_STYLE =
      BaseStyle.Json(
        """
        {
          "version": 8,
          "sources": {},
          "layers": [
            { "id": "bg", "type": "background", "paint": { "background-color": "#000000" } }
          ]
        }
        """
          .trimIndent()
      )
  }
}
