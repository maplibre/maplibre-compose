package org.maplibre.compose.runtime

import org.maplibre.compose.mlnffi.MlnFfiApplication
import org.maplibre.compose.mlnffi.ensureMlnFfiConfigured
import org.maplibre.compose.offline.OfflineManager

/**
 * The application-scoped MapLibre runtime: the tile cache and the offline work that outlive any one
 * map.
 *
 * [default] returns the runtime that every map in this process shares. The platform
 * `MapLibre.configure` function sets the cache location and cache budget before the first use;
 * without it, the runtime uses the platform default configuration.
 */
public class MaplibreRuntime private constructor() {

  /**
   * Offline packs and the ambient cache of this runtime.
   *
   * [OfflineManager.create] renders raster tiles at a pixel ratio of 1 unless the call passes the
   * density of the window that shows the map.
   */
  public val offline: OfflineManager
    get() = MlnFfiApplication.offlineManager

  public companion object {
    private val instance = MaplibreRuntime()

    /**
     * Returns the runtime that every map in this process shares, installing the platform default
     * configuration when none is set.
     *
     * On Android the default configuration needs the application context, so a call before
     * `MapLibre.configure(context)` and before the first composed map fails with
     * [IllegalStateException].
     */
    public fun default(): MaplibreRuntime {
      ensureMlnFfiConfigured()
      return instance
    }
  }
}
