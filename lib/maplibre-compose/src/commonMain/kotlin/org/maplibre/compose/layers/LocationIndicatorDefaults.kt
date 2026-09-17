package org.maplibre.compose.layers

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.Path
import androidx.compose.ui.graphics.vector.PathData
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.dp
import org.maplibre.compose.expressions.ast.Expression
import org.maplibre.compose.expressions.dsl.image
import org.maplibre.compose.expressions.value.ImageValue

/** Static, reusable images for [LocationIndicatorLayer]. */
public object LocationIndicatorDefaults {
  /** A 24 dp dot with a white border. The image is centered on the location. */
  @Composable
  public fun topImage(
    color: Color = Color.Blue,
    borderColor: Color = Color.White,
  ): Expression<ImageValue?> =
    image(
      rememberVectorPainter(
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
        autoMirror = false,
      ) { _, _ ->
        Path(
          pathData =
            PathData {
              moveTo(12f, 3f)
              arcTo(9f, 9f, 0f, true, true, 12f, 21f)
              arcTo(9f, 9f, 0f, true, true, 12f, 3f)
              close()
            },
          fill = SolidColor(color),
          stroke = SolidColor(borderColor),
          strokeLineWidth = 2f,
        )
      }
    )

  /** A north-pointing arrow, centered on the dot and extending beyond its border. */
  @Composable
  public fun bearingImage(color: Color = Color.Blue): Expression<ImageValue?> =
    image(
      rememberVectorPainter(
        defaultWidth = 36.dp,
        defaultHeight = 36.dp,
        viewportWidth = 36f,
        viewportHeight = 36f,
        autoMirror = false,
      ) { _, _ ->
        Path(
          pathData =
            PathData {
              moveTo(18f, 0f)
              lineTo(11f, 12f)
              lineTo(25f, 12f)
              close()
            },
          fill = SolidColor(color),
        )
      }
    )
}
