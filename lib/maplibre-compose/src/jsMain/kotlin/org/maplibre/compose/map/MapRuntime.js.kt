package org.maplibre.compose.map

import androidx.compose.runtime.Composable

/** Browser runtime configuration. */
public actual class MapRuntimeOptions

public actual fun createMapRuntime(options: MapRuntimeOptions): MapRuntime =
  RuntimeImplementation(platformOptions = options, resources = MapRuntimeResources {})

private val processMapRuntime: MapRuntime =
  RuntimeImplementation(platformOptions = null, resources = MapRuntimeResources {})

@Composable public actual fun rememberMapRuntime(): MapRuntime = processMapRuntime
