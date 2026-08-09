package org.maplibre.compose.desktop

import org.maplibre.compose.mlnffi.MlnFfiApplication

/** Process-wide entry point for configuring MapLibre Compose on desktop. */
public object MapLibre {
  /**
   * Installs [options] for every map and offline operation in this process.
   *
   * The first call wins. Repeating the same normalized configuration is a no-op; a conflicting call
   * fails immediately.
   */
  public fun configure(options: DesktopRuntimeOptions) {
    MlnFfiApplication.configure(options.toMlnFfiRuntimeOptions())
  }
}
