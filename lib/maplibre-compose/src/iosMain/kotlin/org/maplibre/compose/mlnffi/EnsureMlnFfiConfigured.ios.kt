package org.maplibre.compose.mlnffi

import androidx.compose.runtime.Composable
import org.maplibre.compose.ios.MapLibre

@Composable
internal actual fun EnsureMlnFfiConfigured() {
  MlnFfiApplication.ensureConfigured { MapLibre.configure() }
}
