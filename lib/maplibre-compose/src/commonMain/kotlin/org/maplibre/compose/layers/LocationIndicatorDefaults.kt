package org.maplibre.compose.layers

import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
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

  /** A soft 28 dp shadow, offset one dp below the location dot. */
  @Composable
  public fun shadowImage(color: Color = Color.Black): Expression<ImageValue?> =
    image(
      rememberVectorPainter(
        defaultWidth = 28.dp,
        defaultHeight = 28.dp,
        viewportWidth = 28f,
        viewportHeight = 28f,
        autoMirror = false,
      ) { _, _ ->
        Path(
          pathData =
            PathData {
              moveTo(14f, 2f)
              arcTo(13f, 13f, 0f, true, true, 14f, 28f)
              arcTo(13f, 13f, 0f, true, true, 14f, 2f)
              close()
            },
          fill =
            Brush.radialGradient(
              0f to color.copy(alpha = color.alpha * 0.4f),
              0.55f to color.copy(alpha = color.alpha * 0.25f),
              1f to color.copy(alpha = 0f),
              center = Offset(14f, 15f),
              radius = 13f,
            ),
        )
      }
    )
}
