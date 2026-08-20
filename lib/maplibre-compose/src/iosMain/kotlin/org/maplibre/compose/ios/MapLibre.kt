package org.maplibre.compose.ios

import org.maplibre.compose.mlnffi.MlnFfiApplication

/** iOS configuration for MapLibre Compose. */
public object MapLibre {
  /**
   * Sets the cache file and ambient cache budget for every map and offline operation in this
   * process.
   *
   * Defaults to [iosCacheFile] and MapLibre's cache budget. A conflicting call throws
   * [IllegalStateException].
   */
  public fun configure(options: IosRuntimeOptions = IosRuntimeOptions(iosCacheFile())) {
    MlnFfiApplication.configure(options.toMlnFfiRuntimeOptions())
  }
}
