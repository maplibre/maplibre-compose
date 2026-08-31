package org.maplibre.compose.android

import android.content.Context
import org.maplibre.compose.mlnffi.AndroidMlnFfiPlatform
import org.maplibre.compose.mlnffi.MlnFfiApplication

/** Android configuration for MapLibre Compose. */
public object MapLibre {
  /**
   * Configures the process-default runtime with [androidCacheFile] and the default cache budget.
   */
  public fun configure(context: Context) {
    configure(AndroidRuntimeOptions(context))
  }

  /**
   * Configures the process-default runtime and its offline operations with [options]. A conflicting
   * call throws `IllegalStateException`.
   */
  public fun configure(options: AndroidRuntimeOptions) {
    AndroidMlnFfiPlatform.initialize(options.context)
    MlnFfiApplication.configure(options.toMlnFfiRuntimeOptions())
  }
}
