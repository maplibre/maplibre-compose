package org.maplibre.compose.map

import androidx.compose.runtime.Composable

@Composable internal actual fun rememberScrollConfig(): ScrollConfig = IosScrollConfig

// ComposeSceneMediator scales UIKit's displacement to physical pixels, then multiplies by 0.01.
private val IosScrollConfig = ScrollConfig { event, _, _ -> event.totalScrollDelta * -100f }
