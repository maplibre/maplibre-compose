package org.maplibre.compose.mlnffi

import android.app.Application
import android.content.Context
import org.maplibre.nativeffi.MaplibreAndroid

/** Process-wide Android services required by the FFI runtime and packaged resource reader. */
internal object AndroidMlnFfiPlatform {
  @Volatile private var application: Application? = null

  val applicationContext: Context
    get() = checkNotNull(application) { "MapLibre Compose has not initialized its Android context" }

  fun initialize(context: Context) {
    if (application != null) return
    synchronized(this) {
      if (application != null) return
      val application =
        context.applicationContext as? Application
          ?: context as? Application
          ?: error("Android application context is unavailable")
      MaplibreAndroid.initialize(application)
      this.application = application
    }
  }
}
