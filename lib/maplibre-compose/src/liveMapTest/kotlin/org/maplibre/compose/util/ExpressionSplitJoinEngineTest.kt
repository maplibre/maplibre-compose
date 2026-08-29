package org.maplibre.compose.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.serialization.json.JsonObject
import org.maplibre.compose.expressions.ast.ExpressionContext
import org.maplibre.compose.expressions.dsl.asString
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.contains
import org.maplibre.compose.expressions.dsl.feature
import org.maplibre.compose.expressions.dsl.join
import org.maplibre.compose.expressions.dsl.split
import org.maplibre.compose.layers.SymbolLayer
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.GeoJsonOptions
import org.maplibre.compose.sources.GeoJsonSource
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.StyleMutationException
import org.maplibre.compose.style.install
import org.maplibre.compose.testing.MapLibreFlavor
import org.maplibre.compose.testing.MapTestResult
import org.maplibre.compose.testing.createMapFixture
import org.maplibre.compose.testing.mapLibreFlavor
import org.maplibre.compose.testing.runMapTest
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.Geometry

/** Applies `split` and `join` to a live layer so MapLibre has to compile them. */
class ExpressionSplitJoinEngineTest {

  @Test
  fun maplibre_accepts_split_and_join_as_layer_expressions(): MapTestResult = runMapTest {
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

      val layer = SymbolLayer("labels", source)
      layer.setFilter(
        feature["cuisine"]
          .asString()
          .split(";")
          .contains(const("tea"))
          .compile(ExpressionContext.None)
      )
      layer.setTextField(
        const(listOf("latitude", "longitude")).join(", ").compile(ExpressionContext.None)
      )
      try {
        style.install(layer)
      } catch (error: IllegalStateException) {
        // The current native-ffi pin predates maplibre-native#4463. A later pin will accept these
        // operators and take the assertions below.
        val reason = error.cause
        if (
          mapLibreFlavor == MapLibreFlavor.NATIVE &&
            reason is StyleMutationException &&
            reason.message?.contains("Unknown expression \"split\"") == true
        ) {
          return@use
        }
        throw error
      }

      assertNotNull(style.getLayer("labels"))
      assertEquals(emptyList(), it.errors, "the map should report nothing")
    }
  }
}
