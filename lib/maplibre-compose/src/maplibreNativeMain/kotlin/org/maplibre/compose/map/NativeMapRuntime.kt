package org.maplibre.compose.map

import org.maplibre.compose.mlnffi.MlnFfiRuntime
import org.maplibre.compose.mlnffi.MlnFfiRuntimeOptions
import org.maplibre.compose.mlnffi.normalized
import org.maplibre.compose.offline.MlnFfiOfflineStorage
import org.maplibre.compose.resource.MapResourceConfig

internal fun createNativeMapRuntime(options: MlnFfiRuntimeOptions): MapRuntime {
  val normalizedOptions = options.normalized()
  val resourceConfig =
    MapResourceConfig(
      normalizedOptions.requestInterceptor,
      normalizedOptions.resourceProvider,
      normalizedOptions.logger,
    )
  val mainDispatcher = normalizedOptions.mainDispatcher ?: platformMainDispatcher()
  val owner = MlnFfiRuntime(normalizedOptions, resourceConfig)
  val offlineStorage = MlnFfiOfflineStorage(owner)
  val runtime =
    MapRuntime(
      platformContext = owner,
      closeResources = {
        owner.close()
        owner.awaitClosed()
      },
      logger = normalizedOptions.logger,
      offlineStorageBackend = offlineStorage,
      mainDispatcher = mainDispatcher,
      createSnapshotterAdapter = { createNativeSnapshotterAdapter(owner) },
      resourceConfig = resourceConfig,
    )
  owner.onFailure = { runtime.close() }
  owner.start()
  return runtime
}

internal val MapRuntime.nativeOwner: MlnFfiRuntime
  get() = platformContext as MlnFfiRuntime
