package org.maplibre.compose.ios

import org.maplibre.compose.mlnffi.MlnFfiApplication

/** Process-wide entry point for configuring MapLibre Compose on iOS. */
public object MapLibre {
  /**
   * Installs [options] for every map and offline operation in this process.
   *
   * The first map or offline manager uses [iosCacheFile] and MapLibre's cache budget. Call this
   * beforehand to choose another file or budget. The first call wins. Repeating the same normalized
   * configuration is a no-op; a conflicting call throws [IllegalStateException].
   */
  public fun configure(options: IosRuntimeOptions = IosRuntimeOptions(iosCacheFile())) {
    MlnFfiApplication.configure(options.toMlnFfiRuntimeOptions())
  }
}
