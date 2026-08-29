package org.maplibre.compose.demoapp.agent

import androidx.compose.runtime.Composable
import org.maplibre.compose.demoapp.DemoAppState

// A browser tab cannot host an HTTP server, so there is no driver here.
@Composable internal actual fun StartAgentDriver(state: DemoAppState) {}
