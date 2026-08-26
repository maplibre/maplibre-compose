package org.maplibre.compose.layers

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.maplibre.compose.expressions.ast.ExpressionContext
import org.maplibre.compose.expressions.dsl.Feature
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.eq
import org.maplibre.compose.expressions.value.StringValue
import org.maplibre.compose.mlnffi.BridgeMapFixture
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.GeoJsonOptions
import org.maplibre.compose.sources.GeoJsonSource
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.MlnFfiStyleBinding
import org.maplibre.compose.util.onMap
import org.maplibre.compose.util.toJsonElement
import org.maplibre.nativeffi.style.StyleLayerVisibility
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.Geometry

/** Every generated property is covered in the shared [LayerPropertyRoundTripTest]. */
class MlnFfiLayerKeyRoundTripTest {

  @Test
  fun the_common_layer_keys_reach_maplibre_before_and_after_attach() {
    val fixture = BridgeMapFixture.create()
    fixture.use {
      it.loadStyle(BaseStyle.Empty)
      val style = assertNotNull(it.style as? MlnFfiStyleBinding, "Errors: ${it.errors}")
      val source =
        GeoJsonSource(
            id = SOURCE_ID,
            data = GeoJsonData.Features(FeatureCollection<Geometry, JsonObject?>()),
            options = GeoJsonOptions(),
          )
          .also { source -> style.addSource(source) }

      val beforeAttach = SymbolLayerDescriptor("before", source)
      beforeAttach.sourceLayer = "places"
      beforeAttach.minZoom = 3f
      beforeAttach.maxZoom = 15f
      beforeAttach.visible = false
      beforeAttach.setFilter(
        (Feature["class"].cast<StringValue>() eq const("park")).compile(ExpressionContext.None)
      )
      style.addLayer(beforeAttach)

      val afterAttach = SymbolLayerDescriptor("after", source)
      style.addLayer(afterAttach)
      afterAttach.sourceLayer = "roads"
      afterAttach.minZoom = 4f
      afterAttach.maxZoom = 16f
      afterAttach.visible = false
      afterAttach.setFilter(
        (Feature["class"].cast<StringValue>() eq const("wood")).compile(ExpressionContext.None)
      )

      beforeAttach.onMap { map ->
        assertEquals("places", map.layerSourceLayer("before"))
        assertEquals(SOURCE_ID, map.layerSourceId("before"))
        assertEquals(3.0, map.layerMinZoom("before"))
        assertEquals(15.0, map.layerMaxZoom("before"))
        assertEquals(StyleLayerVisibility.NONE, map.layerVisibility("before"))
        assertEquals(
          Json.parseToJsonElement("""["==",["get","class"],"park"]"""),
          map.layerFilter("before")?.toJsonElement(),
        )

        assertEquals("roads", map.layerSourceLayer("after"))
        assertEquals(4.0, map.layerMinZoom("after"))
        assertEquals(16.0, map.layerMaxZoom("after"))
        assertEquals(StyleLayerVisibility.NONE, map.layerVisibility("after"))
        assertEquals(
          Json.parseToJsonElement("""["==",["get","class"],"wood"]"""),
          map.layerFilter("after")?.toJsonElement(),
        )
      }
      assertEquals(emptyList(), it.errors, "the map should report nothing")
    }
  }

  private companion object {
    const val SOURCE_ID = "features"
  }
}
