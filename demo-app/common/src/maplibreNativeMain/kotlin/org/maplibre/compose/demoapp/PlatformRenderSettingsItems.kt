package org.maplibre.compose.demoapp

import androidx.compose.runtime.Composable

/** Platform-specific rendering controls and diagnostics. */
@Composable expect fun PlatformRenderSettingsItems(settings: DemoSettings)
