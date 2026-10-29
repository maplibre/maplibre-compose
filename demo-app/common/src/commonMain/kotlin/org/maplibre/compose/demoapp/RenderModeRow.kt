package org.maplibre.compose.demoapp

import androidx.compose.runtime.Composable

/** The app-wide choice of how the map is presented, where a platform offers one. */
@Composable expect fun RenderModeRow(settings: DemoSettings)
