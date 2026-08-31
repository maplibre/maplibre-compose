package org.maplibre.compose.map

import androidx.compose.runtime.Composable
import org.maplibre.compose.android.AndroidRuntimeOptions
import org.maplibre.compose.android.toMlnFfiRuntimeOptions
import org.maplibre.compose.mlnffi.AndroidMlnFfiPlatform
import org.maplibre.compose.mlnffi.EnsureMlnFfiConfigured
import org.maplibre.compose.mlnffi.MlnFfiApplication

public actual typealias MapRuntimeOptions = AndroidRuntimeOptions

public actual fun createMapRuntime(options: MapRuntimeOptions): MapRuntime {
  AndroidMlnFfiPlatform.initialize(options.context)
  return createNativeMapRuntime(options.toMlnFfiRuntimeOptions())
}

@Composable
public actual fun rememberMapRuntime(): MapRuntime {
  EnsureMlnFfiConfigured()
  return ProcessNativeMapRuntime.get(MlnFfiApplication.options)
}
