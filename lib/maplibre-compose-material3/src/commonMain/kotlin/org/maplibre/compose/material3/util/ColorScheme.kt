package org.maplibre.compose.material3.util

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.Stable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse

// The scalebar uses `LocalContentColor` as its default color to be consistent with other elements
// shown alongside it, such as the attribution button, static text, etc.. So, usually it is the
// other way round - the background color decides which content color is used. For the scalebar, we
// look at which content color is used, and derive the scalebar's halo from that, as the halo should
// mimic a background. See also #290.

/** Like [contentColorFor][androidx.compose.material3.contentColorFor] but the other way round */
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
