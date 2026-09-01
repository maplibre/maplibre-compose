package org.maplibre.compose.map

import kotlinx.coroutines.runBlocking
import org.maplibre.compose.mlnffi.MlnFfiLock
import org.maplibre.compose.mlnffi.MlnFfiRuntimeOptions
import org.maplibre.compose.mlnffi.withLock
import org.maplibre.compose.offline.MlnFfiOfflineManager
import org.maplibre.compose.resource.MapResourceConfig

private val nativeMapRuntimeCapabilities =
  MapRuntimeCapabilities(
    supportsOfflinePacks = true,
    supportsAmbientCacheManagement = true,
  )

internal object ProcessNativeMapRuntime {
  private val lock = MlnFfiLock()
  private var current: RuntimeImplementation? = null
  private var currentOptions: MlnFfiRuntimeOptions? = null

  fun get(options: MlnFfiRuntimeOptions): MapRuntime = lock.withLock {
    current?.takeIf { currentOptions == options }
      ?: createNativeMapRuntimeImplementation(options).also {
        currentOptions = options
        current = it
      }
  }

  fun resetForTest(): Boolean {
    val runtime =
      lock.withLock {
        current.also {
          current = null
          currentOptions = null
        }
      } ?: return true
    runtime.close()
    return runCatching { runBlocking { runtime.awaitClosed() } }.isSuccess
  }
}

internal fun createNativeMapRuntime(options: MlnFfiRuntimeOptions): MapRuntime =
  createNativeMapRuntimeImplementation(options)

private fun createNativeMapRuntimeImplementation(
  options: MlnFfiRuntimeOptions
): RuntimeImplementation {
  val resourceConfig = MapResourceConfig(options.requestInterceptor, options.resourceProvider)
  val offlineManager = MlnFfiOfflineManager(options, resourceConfig)
  return RuntimeImplementation(
    platformOptions = options,
    resources =
      MapRuntimeResources {
        check(offlineManager.close()) { "The offline manager did not stop" }
      },
    logger = options.logger,
    capabilities = nativeMapRuntimeCapabilities,
    offlineManagerBackend = offlineManager,
    snapshotterAdapterFactory = NativeSnapshotterAdapterFactory(options, resourceConfig),
    resourceConfig = resourceConfig,
  )
}

internal val RuntimeImplementation.nativeRuntimeOptions: MlnFfiRuntimeOptions
  get() = platformOptions as MlnFfiRuntimeOptions
