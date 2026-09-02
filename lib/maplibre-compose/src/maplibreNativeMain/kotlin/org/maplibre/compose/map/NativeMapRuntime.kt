package org.maplibre.compose.map

import kotlinx.coroutines.runBlocking
import org.maplibre.compose.mlnffi.MlnFfiLock
import org.maplibre.compose.mlnffi.MlnFfiRuntimeOptions
import org.maplibre.compose.mlnffi.normalized
import org.maplibre.compose.mlnffi.withLock
import org.maplibre.compose.offline.MlnFfiOfflineManager
import org.maplibre.compose.resource.MapResourceConfig

internal object DefaultMapRuntime {
  private val lock = MlnFfiLock()
  private var current: RuntimeImplementation? = null

  /** Returns the permanent process-default runtime, including after the caller closes it. */
  fun getOrCreate(defaultOptions: () -> MlnFfiRuntimeOptions): MapRuntime = lock.withLock {
    current
      ?: createNativeMapRuntimeImplementation(defaultOptions().normalized()).also { current = it }
  }

  /** Installs a process-default runtime with controlled options. Tests only. */
  fun installForTest(options: MlnFfiRuntimeOptions): MapRuntime = lock.withLock {
    check(current == null) { "The process-default runtime is already initialized" }
    createNativeMapRuntimeImplementation(options.normalized()).also { current = it }
  }

  fun resetForTest(): Boolean {
    val runtime =
      lock.withLock {
        current.also {
          current = null
        }
      } ?: return true
    runtime.close()
    return runCatching { runBlocking { runtime.awaitClosed() } }.isSuccess
  }
}

internal fun createNativeMapRuntime(options: MlnFfiRuntimeOptions): MapRuntime =
  createNativeMapRuntimeImplementation(options.normalized())

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
    offlineManagerBackend = offlineManager,
    snapshotterAdapterFactory = NativeSnapshotterAdapterFactory(options, resourceConfig),
    resourceConfig = resourceConfig,
  )
}

internal val RuntimeImplementation.nativeRuntimeOptions: MlnFfiRuntimeOptions
  get() = platformOptions as MlnFfiRuntimeOptions
