package org.maplibre.compose.interaction.internal

import androidx.compose.runtime.Composable
import org.maplibre.compose.interaction.HapticEmphasis

/** Called on the Compose input dispatcher. Unsupported platforms deliberately do nothing. */
@Composable internal expect fun rememberBearingHapticFeedback(): (HapticEmphasis) -> Unit
