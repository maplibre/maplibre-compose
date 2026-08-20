package org.maplibre.compose.ios

import org.maplibre.compose.mlnffi.MlnFfiApplication

/** Process-wide entry point for configuring MapLibre Compose on iOS. */
public object MapLibre {
  /**
   * Installs [options] for every map and offline operation in this process.
   *
   * Call this once from application startup, before composing a map or using offline APIs. The
   * first call wins. Repeating the same normalized configuration is a no-op; a conflicting call
   * throws [IllegalStateException].
   */
  public fun configure(options: IosRuntimeOptions = IosRuntimeOptions(iosCacheFile())) {
    MlnFfiApplication.configure(options.toMlnFfiRuntimeOptions())
  }
}
