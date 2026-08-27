package org.maplibre.compose.demoapp.agent

import androidx.compose.runtime.Composable
import org.maplibre.compose.demoapp.DemoAppState

// ktor-server has no iOS artifact we want to depend on, so there is no driver here.
@Composable internal actual fun StartAgentDriver(state: DemoAppState) {}
