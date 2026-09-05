package org.maplibre.compose.material3

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.Interaction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonElevation
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ElevatedButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.toPath
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import org.maplibre.compose.material3.util.proportionalPadding
import org.maplibre.compose.overlay.MapOverlayScope
import org.maplibre.compose.overlay.rememberPlacedTowardsState
import org.maplibre.spatialk.geojson.Position

/**
 * An elevated button in the shape of a pointer pin, placed through
 * [placedTowards][MapOverlayScope.placedTowards] on the edge of an ellipse inscribed in the
 * unobstructed map region and pointing towards [targetPosition]. Only shown while [targetPosition]
 * is outside of the ellipse.
 *
 * @param targetPosition position (off-screen) the pin should point at
 * @param modifier the [Modifier] to be applied to this button
 * @param onClick called when this button is clicked
 * @param enabled controls the enabled state of this button. When `false`, this component will not
 *   respond to user input, and it will appear visually disabled and disabled to accessibility
 *   services.
 * @param colors [ButtonColors] that will be used to resolve the colors for this button in different
 *   states. See [ButtonDefaults.elevatedButtonColors].
 * @param elevation [ButtonElevation] used to resolve the elevation for this button in different
 *   states. This controls the size of the shadow below the button. Additionally, when the container
 *   color is [ColorScheme.surface], this controls the amount of primary color applied as an
 *   overlay. See [ButtonDefaults.elevatedButtonElevation].
 * @param border the border to draw around the container of this button
 * @param contentPadding the spacing values to apply internally between the container and the
 *   content
 * @param interactionSource an optional hoisted [MutableInteractionSource] for observing and
 *   emitting [Interaction]s for this button. You can use this to change the button's appearance or
 *   preview the button in different states. Note that if `null` is provided, interactions will
 *   still happen internally.
 */
@Composable
public fun MapOverlayScope.PointerPinButton(
  targetPosition: Position,
  modifier: Modifier = Modifier,
  onClick: () -> Unit = {},
  enabled: Boolean = true,
  colors: ButtonColors = ButtonDefaults.elevatedButtonColors(),
  elevation: ButtonElevation? = ButtonDefaults.elevatedButtonElevation(),
  border: BorderStroke? = null,
  contentPadding: PaddingValues = PaddingValues(12.dp), // good padding for a 24x24 icon
  interactionSource: MutableInteractionSource? = null,
  content: @Composable (BoxScope.() -> Unit),
) {
  val placement = rememberPlacedTowardsState()

  ElevatedButton(
    onClick = onClick,
    modifier =
      modifier
        .placedTowards(targetPosition, placement)
        // Rotation applies at draw time, after the layout pass writes the angle, so the pin
        // points at the target on the same frame it is placed.
        .graphicsLayer { rotationZ = placement.angleDegrees },
    enabled = enabled,
    shape = PointerPinShape,
    colors = colors,
    elevation = elevation,
    border = border,
    contentPadding = PaddingValues(0.dp),
    interactionSource = interactionSource,
  ) {
    Box(
      modifier =
        Modifier
          // Counter-rotation keeps the content upright inside the rotated pin.
          .graphicsLayer { rotationZ = -placement.angleDegrees }
          // Offset the content from the pin tip to center it in the rounded part.
          .proportionalPadding(PointerPinShape.POINTY_SIZE)
          .padding(contentPadding)
    ) {
      content()
    }
  }
}

/** A kind of map-📍 shape, pointing up; rotation comes from the button's graphics layer. */
private object PointerPinShape : Shape {
  override fun createOutline(
    size: Size,
    layoutDirection: LayoutDirection,
    density: Density,
  ): Outline {
    val m = Matrix()
    m.scale(x = size.width / PATH_SIZE, y = size.height / PATH_SIZE)
    val p = PATH.toPath()
    p.transform(m)
    return Outline.Generic(p)
  }

  const val PATH_SIZE = 76f
  const val POINTY_SIZE = 14f / 76f
  val PATH =
    PathParser()
      .parsePathString(
        "M 38,62 C 24.745,62 14,51.255 14,38 14.003,32.6405 15.7995,27.4365 19.1035,23.217 L 38,0 56.914,23.2715 C 60.2005,27.4785 61.99,32.6615 62,38 62,51.255 51.255,62 38,62 Z"
      )
      .toNodes()
}
