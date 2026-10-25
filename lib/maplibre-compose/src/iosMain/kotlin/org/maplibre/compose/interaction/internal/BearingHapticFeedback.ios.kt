package org.maplibre.compose.interaction.internal

import androidx.compose.ui.hapticfeedback.HapticFeedbackType

internal actual val HapticFeedbackOverride: ((HapticFeedbackType) -> Unit)? = null
