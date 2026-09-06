package org.maplibre.compose.interaction.internal

import androidx.compose.runtime.Composable

@Composable internal actual fun rememberScrollConverter(): ScrollConverter = IosScrollConverter

// ComposeSceneMediator scales UIKit's displacement to physical pixels, then multiplies by 0.01.
private val IosScrollConverter: ScrollConverter = { event, _, _ -> event.totalScrollDelta * -100f }
