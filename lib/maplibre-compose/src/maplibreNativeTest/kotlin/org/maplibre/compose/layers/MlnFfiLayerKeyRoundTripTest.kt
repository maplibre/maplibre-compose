package org.maplibre.compose.layers

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
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
import org.maplibre.compose.sources.GeoJsonSource
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.MlnFfiStyleBinding
import org.maplibre.compose.style.install
import org.maplibre.compose.testing.runMapTest
import org.maplibre.compose.util.toJsonElement
import org.maplibre.nativeffi.StyleLayerVisibility
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.Geometry

/** Covers the native setters for root layer properties. */
class MlnFfiLayerKeyRoundTripTest {

  @Test
  fun the_common_layer_keys_reach_maplibre_before_and_after_attach() = runMapTest {
    val fixture = BridgeMapFixture.create()
    fixture.use {
      it.loadStyle(BaseStyle.Empty)
      val style = assertNotNull(it.style as? MlnFfiStyleBinding, "Errors: ${it.errors}")
      val source =
        GeoJsonSource(
            id = SourceId,
            data = GeoJsonData.Features(FeatureCollection<Geometry, JsonObject?>()),
          )
          .also { source -> style.install(source) }

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
      style.install(beforeAttach)

      val afterAttach = TestLayer("after", "symbol", source)
      val afterHandle = style.install(afterAttach)
      afterAttach.sourceLayer = "roads"
      afterAttach.minZoom = 4f
      afterAttach.maxZoom = 16f
      afterAttach.visible = false
      afterAttach.root(
        "filter",
        ((Feature["class"].cast<StringValue>() eq const("wood")).compile(ExpressionContext.None))
          .asLayerProperty(),
      )
      afterHandle.update(afterAttach.definition())

      val map = style.mapForTest
      val before = assertNotNull(map.getStyleLayer("before").await())
      assertEquals("places", before.sourceLayer)
      assertEquals(SourceId, before.sourceId)
      assertEquals(3.0, before.minZoom)
      assertEquals(15.0, before.maxZoom)
      assertEquals(StyleLayerVisibility.NONE, before.visibility)
      assertEquals(
        Json.parseToJsonElement("""["==",["get","class"],"park"]"""),
        map.getStyleLayerProperty("before", "filter").await()?.toJsonElement(),
      )

      val after = assertNotNull(map.getStyleLayer("after").await())
      assertEquals("roads", after.sourceLayer)
      assertEquals(4.0, after.minZoom)
      assertEquals(16.0, after.maxZoom)
      assertEquals(StyleLayerVisibility.NONE, after.visibility)
      assertEquals(
        Json.parseToJsonElement("""["==",["get","class"],"wood"]"""),
        map.getStyleLayerProperty("after", "filter").await()?.toJsonElement(),
      )
      assertEquals(emptyList(), it.errors, "the map should report nothing")
    }
  }

  private companion object {
    const val SourceId = "features"
  }
}
