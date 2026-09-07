package org.maplibre.compose.interaction.internal

import androidx.compose.runtime.Composable
import org.maplibre.compose.interaction.HapticEmphasis

private val silentFeedback: (HapticEmphasis) -> Unit = {}

@Composable
internal actual fun rememberBearingHapticFeedback(): (HapticEmphasis) -> Unit = silentFeedback
