package org.maplibre.compose.map

import org.maplibre.compose.mlnffi.MlnFfiLock
import org.maplibre.compose.mlnffi.MlnFfiRuntimeOptions
import org.maplibre.compose.mlnffi.withLock

internal object ProcessNativeMapRuntime {
  private val lock = MlnFfiLock()
  private var current: RuntimeImplementation? = null
  private var currentOptions: MlnFfiRuntimeOptions? = null

  fun get(options: MlnFfiRuntimeOptions): MapRuntime = lock.withLock {
    current?.takeIf { currentOptions == options }
      ?: RuntimeImplementation(
          platformOptions = options,
          resources = MapRuntimeResources {},
          logger = options.logger,
        )
        .also {
          currentOptions = options
          current = it
        }
  }
}

internal fun createNativeMapRuntime(options: MlnFfiRuntimeOptions): MapRuntime =
  RuntimeImplementation(
    platformOptions = options,
    resources = MapRuntimeResources {},
    logger = options.logger,
  )

internal val RuntimeImplementation.nativeRuntimeOptions: MlnFfiRuntimeOptions
  get() = platformOptions as MlnFfiRuntimeOptions
