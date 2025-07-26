package org.maplibre.compose.style.layer

import androidx.compose.ui.graphics.Color
import org.maplibre.compose.style.expressions.ast.Expression
import org.maplibre.compose.style.expressions.dsl.const
import org.maplibre.compose.style.expressions.dsl.heatmapDensity
import org.maplibre.compose.style.expressions.dsl.interpolate
import org.maplibre.compose.style.expressions.dsl.linear
import org.maplibre.compose.style.expressions.value.ColorValue
import org.maplibre.compose.style.expressions.value.ListValue
import org.maplibre.compose.style.expressions.value.StringValue

public object LayerDefaults {
  public val HeatmapColors: Expression<ColorValue> =
    interpolate(
      linear(),
      heatmapDensity(),
      0 to const(Color.Companion.Transparent),
      0.1 to const(Color(0xFF4169E1)), // royal blue
      0.3 to const(Color(0xFF00FFFF)), // cyan
      0.5 to const(Color(0xFF00FF00)), // lime
      0.7 to const(Color(0xFFFFFF00)), // yellow
      1 to const(Color(0xFFFF0000)), // red
    )

  public val FontNames: Expression<ListValue<StringValue>> =
    const(listOf("Open Sans Regular", "Arial Unicode MS Regular"))
}
