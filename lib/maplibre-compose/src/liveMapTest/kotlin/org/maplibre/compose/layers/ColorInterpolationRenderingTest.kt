package org.maplibre.compose.layers

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.expressions.ast.ExpressionContext
import org.maplibre.compose.expressions.ast.compile
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.interpolateHcl
import org.maplibre.compose.expressions.dsl.interpolateLab
import org.maplibre.compose.expressions.dsl.linear
import org.maplibre.compose.expressions.dsl.zoom
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.install
import org.maplibre.compose.testing.MapTestResult
import org.maplibre.compose.testing.RgbaPixel
import org.maplibre.compose.testing.createMapFixture
import org.maplibre.compose.testing.pumpUntilPixel
import org.maplibre.compose.testing.runMapTest

class ColorInterpolationRenderingTest {
  @Test
  fun lab_and_hcl_color_ramps_render_the_interpolated_color(): MapTestResult = runMapTest {
    val stops = arrayOf(0 to const(Color.Red), 10 to const(Color.Blue))
    val cases =
      listOf(
        // Midpoints from the pinned style spec's D50 Lab/HCL conversions.
        interpolateLab(linear(), zoom(), *stops) to RgbaPixel(193, 0, 136, 255),
        interpolateHcl(linear(), zoom(), *stops) to RgbaPixel(245, 0, 134, 255),
      )
    for ((expression, color) in cases) {
      createMapFixture().use { fixture ->
        fixture.loadStyle(BaseStyle.Empty)
        fixture.state.setCameraPosition(CameraPosition(zoom = 5.0))
        val layer = TestLayer("color-ramp", "background")
        layer.paint(
          "background-color",
          expression.compile(ExpressionContext.None).asLayerProperty(),
        )
        checkNotNull(fixture.style).install(layer)
        fixture.pumpUntilPixel("the interpolated color", 256, 256, color)
        assertEquals(emptyList(), fixture.errors)
      }
    }
  }
}
