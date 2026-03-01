package org.maplibre.compose.map

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runAndroidComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import org.maplibre.compose.expressions.dsl.asString
import org.maplibre.compose.expressions.dsl.case
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.feature
import org.maplibre.compose.expressions.dsl.interpolate
import org.maplibre.compose.expressions.dsl.linear
import org.maplibre.compose.expressions.dsl.switch
import org.maplibre.compose.expressions.dsl.zoom
import org.maplibre.compose.layers.LineLayer
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
        assertNoLogErrors()
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
            iconImage =
              switch(input = feature["type"].asString(), fallback = const("icon-default")).cast(),
          )
        }
      ) {
        assertNoLogErrors()
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
        assertNoLogErrors()
      }
    }

  /**
   * Regression for #471: iconPadding with DpPadding type. The native SDK previously failed when
   * iconPadding used PaddingValues with dp units.
   */
  @Test
  fun iconPaddingDpType() =
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
            iconPadding = const(PaddingValues.Absolute(4.dp, 4.dp, 4.dp, 4.dp)),
          )
        }
      ) {
        assertNoLogErrors()
      }
    }

  /**
   * Regression for #679: lineProgress expression on line gradient. Requires a GeoJsonSource with
   * lineMetrics enabled. The native SDK previously failed when lineProgress was used in a gradient
   * interpolation.
   */
  @Test
  fun lineProgressExpression() =
    runAndroidComposeUiTest<ComponentActivity> {
      withMap(
        content = {
          val source =
            rememberGeoJsonSource(
              data = GeoJsonData.Features(featureCollectionOf()),
              options = GeoJsonOptions(lineMetrics = true),
            )
          LineLayer(
            id = "test-line",
            source = source,
            gradient =
              interpolate(
                linear(),
                feature.lineProgress(),
                0 to const(Color.Red),
                1 to const(Color.Blue),
              ),
          )
        }
      ) {
        assertNoLogErrors()
      }
    }
}
