package org.maplibre.compose.map

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runAndroidComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.maplibre.android.style.layers.SymbolLayer as MLNSymbolLayer
import org.maplibre.compose.expressions.dsl.asString
import org.maplibre.compose.expressions.dsl.case
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.feature
import org.maplibre.compose.expressions.dsl.interpolate
import org.maplibre.compose.expressions.dsl.linear
import org.maplibre.compose.expressions.dsl.switch
import org.maplibre.compose.expressions.dsl.zoom
import org.maplibre.compose.expressions.value.SymbolAnchor
import org.maplibre.compose.layers.SymbolLayer
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.GeoJsonOptions
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.spatialk.geojson.dsl.featureCollectionOf

@OptIn(ExperimentalTestApi::class)
class ExpressionRegressionTest {

  /**
   * Regression for #310, #327: switch expression with multiple cases on iconImage. The native SDK
   * previously crashed when using `case` expressions with `iconImage` on iOS, and the fix cascades
   * single conditions. This test verifies the expression compiles without error on Android.
   */
  @Test
  fun switchExpressionMultipleCases() =
    runAndroidComposeUiTest<ComponentActivity> {
      withMap(
        content = {
          val source =
            rememberGeoJsonSource(
              data = GeoJsonData.Features(featureCollectionOf()),
              options = GeoJsonOptions(),
            )
          SymbolLayer(
            id = "test-symbol",
            source = source,
            iconImage =
              switch(
                  input = feature["type"].asString(),
                  case("a", const("icon-a")),
                  case("b", const("icon-b")),
                  case("c", const("icon-c")),
                  fallback = const("icon-default"),
                )
                .cast(),
          )
        }
      ) {
        val property = runOnUiThread { (getLayerImpl("test-symbol") as MLNSymbolLayer).iconImage }
        assertTrue { property.isExpression }
        assertEquals(
          // language=JSON
          """["match", ["string", ["get", "type"]], "a", ["image", "icon-a"], "b", ["image", "icon-b"], "c", ["image", "icon-c"], ["image", "icon-default"]]""",
          property.expression.toString(),
        )
      }
    }

  /**
   * Regression for #639: switch expression with zero cases (only fallback). When no cases are
   * provided, the expression optimizes to just the fallback value.
   */
  @Test
  fun switchExpressionZeroCases() =
    runAndroidComposeUiTest<ComponentActivity> {
      withMap(
        content = {
          val source =
            rememberGeoJsonSource(
              data = GeoJsonData.Features(featureCollectionOf()),
              options = GeoJsonOptions(),
            )
          SymbolLayer(
            id = "test-symbol",
            source = source,
            textAnchor =
              switch(input = feature["type"].asString(), fallback = const(SymbolAnchor.Center))
                .cast(),
          )
        }
      ) {
        val property = runOnUiThread { (getLayerImpl("test-symbol") as MLNSymbolLayer).textAnchor }
        assertTrue { property.isValue }
        assertEquals("center", property.value)
      }
    }

  /**
   * Regression for #555, #573: textSize with float interpolation at zoom levels. The native SDK
   * previously rejected or mishandled interpolated text size expressions.
   */
  @Test
  fun textSizeInterpolation() =
    runAndroidComposeUiTest<ComponentActivity> {
      withMap(
        content = {
          val source =
            rememberGeoJsonSource(
              data = GeoJsonData.Features(featureCollectionOf()),
              options = GeoJsonOptions(),
            )
          SymbolLayer(
            id = "test-symbol",
            source = source,
            textSize = interpolate(linear(), zoom(), 10 to const(12f), 20 to const(24f)).cast(),
          )
        }
      ) {
        val property = runOnUiThread { (getLayerImpl("test-symbol") as MLNSymbolLayer).textSize }
        assertTrue { property.isExpression }
        assertEquals(
          // language=JSON
          """["interpolate", ["linear"], ["zoom"], 10.0, 12.0, 20.0, 24.0]""",
          property.expression.toString(),
        )
      }
    }
}
