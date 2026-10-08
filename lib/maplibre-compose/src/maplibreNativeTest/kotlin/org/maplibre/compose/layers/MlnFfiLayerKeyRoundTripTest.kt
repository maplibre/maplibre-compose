package org.maplibre.compose.layers

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.maplibre.compose.expressions.ast.ExpressionContext
import org.maplibre.compose.expressions.ast.compile
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
import org.maplibre.compose.style.install
import org.maplibre.compose.style.onOwner
import org.maplibre.compose.util.onMap
import org.maplibre.compose.util.toJsonElement
import org.maplibre.nativeffi.style.StyleLayerVisibility
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.Geometry

/** Covers the native setters for root layer properties. */
class MlnFfiLayerKeyRoundTripTest {

  @Test
  fun the_common_layer_keys_reach_maplibre_before_and_after_attach() {
    val fixture = BridgeMapFixture.create()
    fixture.use {
      it.loadStyle(BaseStyle.Empty)
      val style = assertNotNull(it.style as? MlnFfiStyleBinding, "Errors: ${it.errors}")
      val source =
        GeoJsonSource(
            id = SourceId,
            data = GeoJsonData.Features(FeatureCollection<Geometry, JsonObject?>()),
            options = GeoJsonOptions.Standard,
          )
          .also { source -> runBlocking { style.install(source) } }

      val beforeAttach = TestLayer("before", "symbol", source)
      beforeAttach.sourceLayer = "places"
      beforeAttach.minZoom = 3f
      beforeAttach.maxZoom = 15f
      beforeAttach.visible = false
      beforeAttach.root(
        "filter",
        ((Feature["class"].cast<StringValue>() eq const("park")).compile(ExpressionContext.None))
          .asLayerProperty(),
      )
      runBlocking { style.install(beforeAttach) }

      val afterAttach = TestLayer("after", "symbol", source)
      val afterHandle = runBlocking { style.install(afterAttach) }
      afterAttach.sourceLayer = "roads"
      afterAttach.minZoom = 4f
      afterAttach.maxZoom = 16f
      afterAttach.visible = false
      afterAttach.root(
        "filter",
        ((Feature["class"].cast<StringValue>() eq const("wood")).compile(ExpressionContext.None))
          .asLayerProperty(),
      )
      runBlocking { style.onOwner { afterHandle.update(afterAttach.definition()) } }

      style.onMap { map ->
        assertEquals("places", map.layerSourceLayer("before"))
        assertEquals(SourceId, map.layerSourceId("before"))
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

        afterAttach.minZoom = 5f
        afterHandle.update(afterAttach.definition())
        assertEquals(
          5.0,
          map.layerMinZoom("after"),
          "nested style writes must finish inside the current owner operation",
        )
      }
      assertEquals(emptyList(), it.errors, "the map should report nothing")
    }
  }

  private companion object {
    const val SourceId = "features"
  }
}
