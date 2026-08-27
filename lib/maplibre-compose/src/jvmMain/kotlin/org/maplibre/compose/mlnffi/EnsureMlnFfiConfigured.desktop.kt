package org.maplibre.compose.mlnffi

import androidx.compose.runtime.Composable
import org.maplibre.compose.desktop.desktopRuntimeOptions

@Composable internal actual fun CaptureMlnFfiPlatformContext(): Unit = Unit

internal actual fun ensureMlnFfiConfigured() {
  MlnFfiApplication.ensureConfigured { desktopRuntimeOptions() }
}
