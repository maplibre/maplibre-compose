package org.maplibre.compose.mlnffi

import androidx.compose.runtime.Composable
import org.maplibre.compose.desktop.desktopRuntimeOptions

@Composable
internal actual fun EnsureMlnFfiConfigured() {
  MlnFfiApplication.ensureConfigured { desktopRuntimeOptions() }
}
