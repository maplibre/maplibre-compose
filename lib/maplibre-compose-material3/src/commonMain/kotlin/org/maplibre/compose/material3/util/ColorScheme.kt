package org.maplibre.compose.material3.util

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.Stable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse

/** Chooses the scale bar halo from its content color. See #290. */
@Composable
@ReadOnlyComposable
internal fun backgroundColorFor(contentColor: Color) =
  MaterialTheme.colorScheme.backgroundColorFor(contentColor).takeOrElse {
    MaterialTheme.colorScheme.background
  }

@Stable
internal fun ColorScheme.backgroundColorFor(contentColor: Color): Color =
  when (contentColor) {
    onPrimary -> primary
    onSecondary -> secondary
    onTertiary -> tertiary
    onBackground -> background
    onError -> error
    onPrimaryContainer -> primaryContainer
    onSecondaryContainer -> secondaryContainer
    onTertiaryContainer -> tertiaryContainer
    onErrorContainer -> errorContainer
    inverseOnSurface -> inverseSurface
    onSurface -> surface
    onSurfaceVariant -> surfaceVariant
    else -> Color.Unspecified
  }
