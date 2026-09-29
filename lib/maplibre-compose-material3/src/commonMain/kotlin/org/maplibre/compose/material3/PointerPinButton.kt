package org.maplibre.compose.material3

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.maplibre.compose.overlay.MapOverlayScope
import org.maplibre.compose.overlay.PointerPinButton as BasePointerPinButton
import org.maplibre.compose.overlay.PointerPinButtonStyle
import org.maplibre.spatialk.geojson.Position

/**
 * A button in the shape of a pointer pin, placed through
 * [placedTowards][MapOverlayScope.placedTowards] on the edge of an ellipse inscribed in its layout
 * bounds and pointing towards [targetPosition]. Only shown while [targetPosition] is outside of the
 * ellipse. The content stays upright while the pin turns.
 *
 * This is [org.maplibre.compose.overlay.PointerPinButton] with the colors, elevation, and text
 * style of an [ElevatedButton].
 *
 * @param targetPosition Position (off-screen) the pin points at.
 * @param onClick Called when the button is clicked.
 * @param colors Container and content colors, defaulting to those of an [ElevatedButton].
 * @param contentPadding Gap between the round part of the pin and the content.
 */
@Composable
public fun MapOverlayScope.PointerPinButton(
  targetPosition: Position,
  modifier: Modifier = Modifier,
  onClick: () -> Unit = {},
  colors: ButtonColors = ButtonDefaults.elevatedButtonColors(),
  contentPadding: PaddingValues = PaddingValues(12.dp), // good padding for a 24x24 icon
  content: @Composable BoxScope.() -> Unit,
) {
  // The ripple reads the content color where it draws, inside the base button.
  CompositionLocalProvider(
    LocalContentColor provides colors.contentColor,
    LocalTextStyle provides LocalTextStyle.current.merge(MaterialTheme.typography.labelLarge),
  ) {
    BasePointerPinButton(
      targetPosition = targetPosition,
      modifier = modifier,
      onClick = onClick,
      style = elevatedButtonStyle(colors),
      contentPadding = contentPadding,
      content = content,
    )
  }
}

/**
 * The elevations are the ones [ButtonDefaults.elevatedButtonElevation] resolves to. They are not
 * reachable through [ButtonColors], and [ElevatedButton] changes elevation on hover alone.
 */
private fun elevatedButtonStyle(colors: ButtonColors) =
  PointerPinButtonStyle(
    containerColor = colors.containerColor,
    shadowElevation = 1.dp,
    hoveredShadowElevation = 3.dp,
  )
