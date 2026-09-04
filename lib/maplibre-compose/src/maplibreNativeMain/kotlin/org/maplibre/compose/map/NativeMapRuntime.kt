package org.maplibre.compose.map

import org.maplibre.compose.mlnffi.MlnFfiRuntimeOptions
import org.maplibre.compose.mlnffi.normalized
import org.maplibre.compose.offline.MlnFfiOfflineManager
import org.maplibre.compose.resource.MapResourceConfig

internal fun createNativeMapRuntime(options: MlnFfiRuntimeOptions): MapRuntime =
  createNativeMapRuntimeImplementation(options.normalized())

private fun createNativeMapRuntimeImplementation(
  options: MlnFfiRuntimeOptions
): RuntimeImplementation {
  val resourceConfig =
    MapResourceConfig(options.requestInterceptor, options.resourceProvider, options.logger)
  val offlineManager = MlnFfiOfflineManager(options, resourceConfig)
  return RuntimeImplementation(
    platformOptions = options,
    resources =
      MapRuntimeResources {
        check(offlineManager.close()) { "The offline manager did not stop" }
      },
    logger = options.logger,
    offlineManagerBackend = offlineManager,
    snapshotterAdapterFactory = NativeSnapshotterAdapterFactory(options, resourceConfig),
    resourceConfig = resourceConfig,
  )
}

internal val RuntimeImplementation.nativeRuntimeOptions: MlnFfiRuntimeOptions
  get() = platformOptions as MlnFfiRuntimeOptions
