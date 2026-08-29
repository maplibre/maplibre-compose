package org.maplibre.compose.map

import androidx.compose.runtime.Composable
import org.maplibre.compose.desktop.DesktopRuntimeOptions
import org.maplibre.compose.desktop.toMlnFfiRuntimeOptions
import org.maplibre.compose.mlnffi.EnsureMlnFfiConfigured
import org.maplibre.compose.mlnffi.MlnFfiApplication

public actual typealias MapRuntimeOptions = DesktopRuntimeOptions

public actual fun createMapRuntime(options: MapRuntimeOptions): MapRuntime =
  createNativeMapRuntime(options.toMlnFfiRuntimeOptions())

@Composable
public actual fun rememberMapRuntime(): MapRuntime {
  EnsureMlnFfiConfigured()
  return ProcessNativeMapRuntime.get(MlnFfiApplication.options)
}
