package org.maplibre.compose.mlnffi

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import org.maplibre.compose.android.AndroidRuntimeOptions
import org.maplibre.compose.android.toMlnFfiRuntimeOptions

@Composable
internal actual fun EnsureMlnFfiConfigured() {
  val context = LocalContext.current
  AndroidMlnFfiPlatform.initialize(context)
  MlnFfiApplication.ensureConfigured {
    AndroidRuntimeOptions(context).toMlnFfiRuntimeOptions()
  }
}
