package org.maplibre.compose.android

import android.content.Context
import org.maplibre.compose.mlnffi.AndroidMlnFfiPlatform
import org.maplibre.compose.mlnffi.MlnFfiApplication

/** Android configuration for MapLibre Compose. */
public object MapLibre {
  /**
   * Sets the cache file and ambient cache budget for every map and offline operation in this
   * process.
   *
   * Defaults to [androidCacheFile] and MapLibre's cache budget. A conflicting call throws
   * [IllegalStateException].
   */
  public fun configure(
    context: Context,
    options: AndroidRuntimeOptions = AndroidRuntimeOptions(androidCacheFile(context)),
  ) {
    AndroidMlnFfiPlatform.initialize(context)
    MlnFfiApplication.configure(options.toMlnFfiRuntimeOptions())
  }
}
