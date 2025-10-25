package org.maplibre.compose.material3

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.Interaction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonElevation
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ElevatedButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.toPath
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import org.maplibre.compose.camera.CameraState
import org.maplibre.compose.material3.util.findEllipsisIntersection
import org.maplibre.compose.material3.util.proportionalAbsoluteOffset
import org.maplibre.compose.material3.util.proportionalPadding
import org.maplibre.compose.material3.util.toDpOffset
import org.maplibre.compose.material3.util.toOffset
import org.maplibre.spatialk.geojson.Position

/**
 * An elevated button in the shape of a pointer pin on the edge of an ellipsis drawn inside the
 * parent layout, pointing towards some [targetPosition] off-screen. Only shown if the
 * [targetPosition] is outside of the ellipsis.
 *
 * Note that this composable should fill the whole area in which the pointer pin button will be
 * **placed**, i.e. usually the whole map screen.
 *
 * @param onClick called when this button is clicked
 * @param cameraState used to calculate where the given [targetPosition] is in screen coordinates
 * @param targetPosition position (off-screen) the pin should point at
 * @param modifier the [Modifier] to be applied to this button
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
public fun PointerPinButton(
  cameraState: CameraState,
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
  val dpTarget =
    remember(targetPosition, cameraState.position) {
      cameraState.projection?.screenLocationFromPosition(targetPosition)
    }
  val target = dpTarget?.toOffset() ?: return
  var area by remember { mutableStateOf<Rect?>(null) }

  Box(modifier = modifier.fillMaxSize().onGloballyPositioned { area = it.boundsInParent() }) {
    val intersection = remember(target, area) { area?.let { findEllipsisIntersection(it, target) } }

    intersection?.let { (offset, angle) ->
      val rotation = angle * 180 / PI
      val dpOffset = offset.toDpOffset()

      val pointerPinShape = remember(rotation) { PointerPinShape(rotation.toFloat()) }

      ElevatedButton(
        onClick = onClick,
        modifier =
          modifier
            // offsetting it to account for the pointy side of the pin depending on the rotation.
            // (The tip of the pin should be exactly on the ellipsis outline)
            .proportionalAbsoluteOffset(
              x = (-sin(angle) / 2.0 - 0.5).toFloat(),
              y = (cos(angle) / 2.0 - 0.5).toFloat(),
            )
            // offsetting the whole pin to place it correctly within the parent layout
            .absoluteOffset(dpOffset.x, dpOffset.y),
        enabled = enabled,
        shape = pointerPinShape,
        colors = colors,
        elevation = elevation,
        border = border,
        contentPadding = PaddingValues(0.dp),
        interactionSource = interactionSource,
      ) {
        Box(
          modifier =
            Modifier
              // padding to place the content within the pin correctly, taking into account that the
              // center of the pointer shape is not the center of to-be-placed icon (due to the
              // pointy side)
              .proportionalPadding(PointerPinShape.POINTY_SIZE)
              .padding(contentPadding)
        ) {
          content()
        }
      }
    }
  }
}

/** A kind of map-📍 shape, but rotatable */
private class PointerPinShape(val rotation: Float = 0f) : Shape {
  override fun createOutline(
    size: Size,
    layoutDirection: LayoutDirection,
    density: Density,
  ): Outline {
    val m = Matrix()
    val halfWidth = size.width / 2
    val halfHeight = size.height / 2
    m.translate(halfWidth, halfHeight)
    m.rotateZ(rotation)
    m.translate(-halfWidth, -halfHeight)
    m.scale(x = size.width / PATH_SIZE, y = size.height / PATH_SIZE)
    val p = PATH.toPath()
    p.transform(m)
    return Outline.Generic(p)
  }

  companion object {
    const val PATH_SIZE = 76f
    const val POINTY_SIZE = 14f / 76f
    val PATH =
      PathParser()
        .parsePathString(
          "M 38,62 C 24.745,62 14,51.255 14,38 14.003,32.6405 15.7995,27.4365 19.1035,23.217 L 38,0 56.914,23.2715 C 60.2005,27.4785 61.99,32.6615 62,38 62,51.255 51.255,62 38,62 Z"
        )
        .toNodes()
  }
}
