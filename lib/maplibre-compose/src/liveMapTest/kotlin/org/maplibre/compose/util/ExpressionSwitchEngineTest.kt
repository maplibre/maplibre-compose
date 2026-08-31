package org.maplibre.compose.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.serialization.json.JsonObject
import org.maplibre.compose.expressions.ast.ExpressionContext
import org.maplibre.compose.expressions.dsl.asString
import org.maplibre.compose.expressions.dsl.condition
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.eq
import org.maplibre.compose.expressions.dsl.feature
import org.maplibre.compose.expressions.dsl.image
import org.maplibre.compose.expressions.dsl.switch
import org.maplibre.compose.layers.SymbolLayer
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.GeoJsonOptions
import org.maplibre.compose.sources.GeoJsonSource
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.install
import org.maplibre.compose.testing.MapTestResult
import org.maplibre.compose.testing.createMapFixture
import org.maplibre.compose.testing.runMapTest
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.Geometry

/**
 * Applies a multi-condition `switch` of `image` expressions to `iconImage`. The old iOS SDK crashed
 * on that shape ([#310](https://github.com/maplibre/maplibre-compose/issues/310)).
 */
class ExpressionSwitchEngineTest {

  @Test
  fun maplibre_accepts_a_multi_condition_image_switch_as_icon_image(): MapTestResult = runMapTest {
    createMapFixture().use {
      it.loadStyle(BaseStyle.Empty)
      val style = assertNotNull(it.style, "Errors: ${it.errors}")
      val source =
        GeoJsonSource(
          id = "features",
          data = GeoJsonData.Features(FeatureCollection<Geometry, JsonObject?>()),
          options = GeoJsonOptions(),
        )
      style.install(source)

      val layer = SymbolLayer("markers", source)
      layer.setIconImage(
        switch(
            condition(feature["icon"].asString() eq const("1"), image("one")),
            condition(feature["icon"].asString() eq const("2"), image("two")),
            condition(feature["icon"].asString() eq const("3"), image("three")),
            fallback = image("fallback"),
          )
          .compile(ExpressionContext.None)
      )
      style.install(layer)

      assertNotNull(style.getLayer("markers"))
      assertEquals(emptyList(), it.errors, "the map should report nothing")
    }
  }
}
