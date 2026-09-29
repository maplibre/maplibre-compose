package org.maplibre.compose.overlay

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.toPath
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import org.maplibre.compose.util.proportionalPadding
import org.maplibre.spatialk.geojson.Position

/**
 * A button in the shape of a pointer pin, placed through
 * [placedTowards][MapOverlayScope.placedTowards] on the edge of an ellipse inscribed in its layout
 * bounds and pointing towards [targetPosition]. Only shown while [targetPosition] is outside of the
 * ellipse. The content stays upright while the pin turns.
 *
 * This component draws with Compose Foundation alone. The Material 3 module provides a themed
 * version of it.
 *
 * @param targetPosition Position (off-screen) the pin points at.
 * @param onClick Called when the button is clicked.
 * @param style Colors and elevation of the pin.
 * @param contentPadding Gap between the round part of the pin and the content.
 */
@Composable
public fun MapOverlayScope.PointerPinButton(
  targetPosition: Position,
  modifier: Modifier = Modifier,
  onClick: () -> Unit = {},
  style: PointerPinButtonStyle = PointerPinButtonDefaults.style(),
  contentPadding: PaddingValues = PaddingValues(12.dp), // good padding for a 24x24 icon
  content: @Composable BoxScope.() -> Unit,
) {
  val placement = rememberPlacedTowardsState()
  val interactionSource = remember { MutableInteractionSource() }
  val hovered by interactionSource.collectIsHoveredAsState()
  val shadowElevation by
    animateDpAsState(if (hovered) style.hoveredShadowElevation else style.shadowElevation)

  // The layers read the angle after the layout pass writes it, so the pin points at the target on
  // the same frame it is placed. The caller's modifier sits on the clickable node, and min
  // constraints reach the layers, so a pin sized by the caller fills and centers its content.
  Box(
    Modifier.placedTowards(targetPosition, state = placement)
      .then(modifier)
      .clickable(
        interactionSource = interactionSource,
        indication = null,
        role = Role.Button,
        onClick = onClick,
      ),
    propagateMinConstraints = true,
  ) {
    // Android 9 and older drop the shadow of a path they don't recognize as convex, and clip to a
    // path without antialiasing. Rotating the layer keeps the upright path they recognize, and
    // drawing the pin instead of clipping it keeps its edge smooth.
    Box(
      Modifier.matchParentSize()
        .graphicsLayer {
          rotationZ = placement.angleDegrees
          this.shadowElevation = shadowElevation.toPx()
          shape = PointerPinShape.Upright
        }
        .background(style.containerColor, PointerPinShape.Upright)
    )
    // Older Android versions clip the children of a rotated layer to the unrotated outline, so the
    // content clips to a rotated outline in a layer that isn't rotated. The clip bounds the ripple.
    Box(
      Modifier.graphicsLayer {
          shape = PointerPinShape(placement.angleDegrees)
          clip = true
        }
        .indication(interactionSource, LocalIndication.current),
      contentAlignment = Alignment.Center,
    ) {
      Box(
        // Keeps the content inside the round part of the pin, centered in the pin's bounds.
        Modifier.proportionalPadding(PointerPinShape.POINTY_SIZE).padding(contentPadding),
        contentAlignment = Alignment.Center,
        content = content,
      )
    }
  }
}

public object PointerPinButtonDefaults {
  /** Reads over both light and dark basemaps, in the absence of a theme to draw colors from. */
  public val ContainerColor: Color = Color.White.copy(alpha = 0.9f)

  public val ShadowElevation: Dp = 0.dp

  public val HoveredShadowElevation: Dp = 0.dp

  public fun style(): PointerPinButtonStyle = PointerPinButtonStyle()
}

@Immutable
public data class PointerPinButtonStyle(
  /** Color of the pin behind the content. */
  public val containerColor: Color = PointerPinButtonDefaults.ContainerColor,

  /** Shadow elevation of the pin at rest. */
  public val shadowElevation: Dp = PointerPinButtonDefaults.ShadowElevation,

  /** Shadow elevation of the pin while a pointer hovers over it. */
  public val hoveredShadowElevation: Dp = PointerPinButtonDefaults.HoveredShadowElevation,
)

/** A kind of map-📍 shape, turned [angleDegrees] clockwise from pointing up. */
private data class PointerPinShape(val angleDegrees: Float) : Shape {
  override fun createOutline(
    size: Size,
    layoutDirection: LayoutDirection,
    density: Density,
  ): Outline {
    val m = Matrix()
    m.translate(x = size.width / 2, y = size.height / 2)
    m.rotateZ(angleDegrees)
    m.translate(x = -size.width / 2, y = -size.height / 2)
    m.scale(x = size.width / PATH_SIZE, y = size.height / PATH_SIZE)
    val p = PATH.toPath()
    p.transform(m)
    return Outline.Generic(p)
  }

  companion object {
    val Upright = PointerPinShape(0f)
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
