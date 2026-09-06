package org.maplibre.compose.map

import org.maplibre.compose.mlnffi.MlnFfiRuntimeOptions
import org.maplibre.compose.mlnffi.normalized
import org.maplibre.compose.offline.MlnFfiOfflineManager
import org.maplibre.compose.resource.MapResourceConfig

internal fun createNativeMapRuntime(options: MlnFfiRuntimeOptions): MapRuntime {
  val normalizedOptions = options.normalized()
  val resourceConfig =
    MapResourceConfig(
      normalizedOptions.requestInterceptor,
      normalizedOptions.resourceProvider,
      normalizedOptions.logger,
    )
  val offlineManager = MlnFfiOfflineManager(normalizedOptions, resourceConfig)
  return RuntimeImplementation(
    platformContext = normalizedOptions,
    closeResources = {
      check(offlineManager.close()) { "The offline manager did not stop" }
    },
    logger = normalizedOptions.logger,
    offlineManagerBackend = offlineManager,
    createSnapshotterAdapter = {
      createNativeSnapshotterAdapter(normalizedOptions, resourceConfig)
    },
    resourceConfig = resourceConfig,
  )
}

internal val RuntimeImplementation.nativeRuntimeOptions: MlnFfiRuntimeOptions
  get() = platformContext as MlnFfiRuntimeOptions
