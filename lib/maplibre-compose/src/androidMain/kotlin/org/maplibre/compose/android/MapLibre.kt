package org.maplibre.compose.android

import android.content.Context
import org.maplibre.compose.mlnffi.AndroidMlnFfiPlatform
import org.maplibre.compose.mlnffi.MlnFfiApplication

/** Process-wide entry point for configuring MapLibre Compose on Android. */
public object MapLibre {
  /**
   * Installs [options] for every map and offline operation in this process.
   *
   * The first map or offline manager uses [androidCacheFile] and MapLibre's cache budget. Call this
   * beforehand to choose another file or budget. The first call wins. Repeating the same normalized
   * configuration is a no-op; a conflicting call throws [IllegalStateException].
   */
  public fun configure(
    context: Context,
    options: AndroidRuntimeOptions = AndroidRuntimeOptions(androidCacheFile(context)),
  ) {
    AndroidMlnFfiPlatform.initialize(context)
    MlnFfiApplication.configure(options.toMlnFfiRuntimeOptions())
  }
}
