package org.maplibre.compose.mlnffi

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import org.maplibre.compose.android.MapLibre

@Composable
internal actual fun EnsureMlnFfiConfigured() {
  val context = LocalContext.current
  MlnFfiApplication.ensureConfigured { MapLibre.configure(context) }
}
