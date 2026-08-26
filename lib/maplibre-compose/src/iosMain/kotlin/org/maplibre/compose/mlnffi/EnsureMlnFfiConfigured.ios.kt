package org.maplibre.compose.mlnffi

import androidx.compose.runtime.Composable
import org.maplibre.compose.ios.IosRuntimeOptions
import org.maplibre.compose.ios.iosCacheFile
import org.maplibre.compose.ios.toMlnFfiRuntimeOptions

@Composable
internal actual fun EnsureMlnFfiConfigured() {
  MlnFfiApplication.ensureConfigured {
    IosRuntimeOptions(iosCacheFile()).toMlnFfiRuntimeOptions()
  }
}

internal actual fun ensureMlnFfiDefaultConfigured() {
  MlnFfiApplication.ensureConfigured {
    IosRuntimeOptions(iosCacheFile()).toMlnFfiRuntimeOptions()
  }
}
