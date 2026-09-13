package org.maplibre.compose.interaction.internal

import androidx.compose.runtime.Composable

// Native Compose forwards NSEvent.deltaX/Y directly, unlike the UIKit conversion.
@Composable
internal actual fun rememberScrollConverter(): ScrollConverter = { event, density, _ ->
  event.totalScrollDelta * (-10f * density.density)
}
