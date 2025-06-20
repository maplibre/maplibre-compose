package dev.sargunv.maplibrecompose.expressions

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.sargunv.maplibrecompose.expressions.ast.Expression
import dev.sargunv.maplibrecompose.expressions.dsl.const
import dev.sargunv.maplibrecompose.expressions.dsl.heatmapDensity
import dev.sargunv.maplibrecompose.expressions.dsl.interpolate
import dev.sargunv.maplibrecompose.expressions.dsl.linear
import dev.sargunv.maplibrecompose.expressions.value.ColorValue
import dev.sargunv.maplibrecompose.expressions.value.ListValue
import dev.sargunv.maplibrecompose.expressions.value.StringValue

public val ZeroPadding: PaddingValues.Absolute = PaddingValues.Absolute(0.dp, 0.dp, 0.dp, 0.dp)

public val DefaultIconPadding: PaddingValues.Absolute =
  PaddingValues.Absolute(2.dp, 2.dp, 2.dp, 2.dp)

public object Defaults {
  public val HeatmapColors: Expression<ColorValue> =
    interpolate(
      linear(),
      heatmapDensity(),
      0 to const(Color.Transparent),
      0.1 to const(Color(0xFF4169E1)), // royal blue
      0.3 to const(Color(0xFF00FFFF)), // cyan
      0.5 to const(Color(0xFF00FF00)), // lime
      0.7 to const(Color(0xFFFFFF00)), // yellow
      1 to const(Color(0xFFFF0000)), // red
    )

  public val FontNames: Expression<ListValue<StringValue>> =
    const(listOf("Open Sans Regular", "Arial Unicode MS Regular"))
}
