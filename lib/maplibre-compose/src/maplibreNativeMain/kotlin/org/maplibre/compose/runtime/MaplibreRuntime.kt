package org.maplibre.compose.runtime

import org.maplibre.compose.mlnffi.MlnFfiApplication
import org.maplibre.compose.mlnffi.ensureMlnFfiConfigured
import org.maplibre.compose.offline.OfflineManager

/**
 * The application-scoped MapLibre runtime: the tile cache and the offline work that outlive any one
 * map. Every map in this process shares it.
 *
 * The platform `MapLibre.configure` function sets the cache location and cache budget before the
 * first use; without it, the runtime uses the platform default configuration.
 */
public object MaplibreRuntime {

  /**
   * Offline packs and the ambient cache of this runtime.
   *
   * The first read installs the platform default configuration when none is set. On Android the
   * default configuration needs the application context, so a read before
   * `MapLibre.configure(context)` and before the first composed map fails with
   * [IllegalStateException].
   *
   * [OfflineManager.create] renders raster tiles at a pixel ratio of 1 unless the call passes the
   * density of the window that shows the map.
   */
  public val offline: OfflineManager
    get() {
      ensureMlnFfiConfigured()
      return MlnFfiApplication.offlineManager
    }
}
