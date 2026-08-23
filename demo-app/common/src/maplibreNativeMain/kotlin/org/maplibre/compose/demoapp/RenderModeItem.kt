package org.maplibre.compose.demoapp

import androidx.compose.runtime.Composable

/**
 * The texture-versus-surface choice, as a settings list item. Android presents the map both ways;
 * every other platform has one presentation, so the actuals there are empty.
 */
@Composable expect fun RenderModeItem(settings: DemoSettings)
