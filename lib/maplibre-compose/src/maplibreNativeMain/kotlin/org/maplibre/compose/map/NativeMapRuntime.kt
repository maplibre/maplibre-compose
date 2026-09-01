package org.maplibre.compose.map

import kotlinx.coroutines.runBlocking
import org.maplibre.compose.mlnffi.MlnFfiLock
import org.maplibre.compose.mlnffi.MlnFfiRuntimeOptions
import org.maplibre.compose.mlnffi.withLock

internal object ProcessNativeMapRuntime {
  private val lock = MlnFfiLock()
  private var current: RuntimeImplementation? = null
  private var currentOptions: MlnFfiRuntimeOptions? = null

  fun get(options: MlnFfiRuntimeOptions): MapRuntime = lock.withLock {
    current?.takeIf { currentOptions == options }
      ?: run {
        current?.close()
        RuntimeImplementation(
            platformOptions = options,
            resources = MapRuntimeResources {},
            logger = options.logger,
            snapshotterAdapterFactory = NativeSnapshotterAdapterFactory(options),
          )
          .also {
            currentOptions = options
            current = it
          }
      }
  }

  /**
   * Tests only. Closes the process runtime before
   * [org.maplibre.compose.mlnffi.MlnFfiApplication.resetForTest].
   */
  fun resetForTest() {
    val previous = lock.withLock {
      val runtime = current
      current = null
      currentOptions = null
      runtime
    }
    previous?.close()
    if (previous != null) runBlocking { previous.awaitClosed() }
  }
}

internal fun createNativeMapRuntime(options: MlnFfiRuntimeOptions): MapRuntime =
  RuntimeImplementation(
    platformOptions = options,
    resources = MapRuntimeResources {},
    logger = options.logger,
    snapshotterAdapterFactory = NativeSnapshotterAdapterFactory(options),
  )

internal val RuntimeImplementation.nativeRuntimeOptions: MlnFfiRuntimeOptions
  get() = platformOptions as MlnFfiRuntimeOptions
