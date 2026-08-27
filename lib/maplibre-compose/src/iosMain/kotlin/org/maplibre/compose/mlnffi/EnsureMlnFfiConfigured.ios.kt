package org.maplibre.compose.mlnffi

import androidx.compose.runtime.Composable
import org.maplibre.compose.ios.IosRuntimeOptions
import org.maplibre.compose.ios.iosCacheFile
import org.maplibre.compose.ios.toMlnFfiRuntimeOptions

@Composable internal actual fun CaptureMlnFfiPlatformContext(): Unit = Unit

internal actual fun ensureMlnFfiConfigured() {
  MlnFfiApplication.ensureConfigured {
    IosRuntimeOptions(iosCacheFile()).toMlnFfiRuntimeOptions()
  }
}
