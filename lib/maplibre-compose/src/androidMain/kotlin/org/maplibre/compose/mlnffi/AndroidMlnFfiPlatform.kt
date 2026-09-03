package org.maplibre.compose.mlnffi

import android.content.Context
import org.maplibre.nativeffi.MaplibreAndroid

/** Process-wide Android services required by the FFI runtime and packaged resource reader. */
internal object AndroidMlnFfiPlatform {
  @Volatile private var initialized = false

  /** The application context that [AndroidContextProvider] captured at process start. */
  val applicationContext: Context
    get() = AndroidContextProvider.context

  /** Hands the application context to MapLibre Native once per process. */
  fun initialize() {
    if (initialized) return
    synchronized(this) {
      if (initialized) return
      MaplibreAndroid.initialize(applicationContext)
      initialized = true
    }
  }
}
