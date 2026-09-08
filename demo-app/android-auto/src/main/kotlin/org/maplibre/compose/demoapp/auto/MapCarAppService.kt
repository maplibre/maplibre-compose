package org.maplibre.compose.demoapp.auto

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.SessionInfo
import androidx.car.app.validation.HostValidator
import org.maplibre.compose.map.MapRuntime
import org.maplibre.compose.map.MapRuntimeOptions
import org.maplibre.compose.map.createMapRuntime

/** A projected POI app: each car session owns its map, while sessions share the runtime cache. */
class MapCarAppService : CarAppService() {
  private var runtime: MapRuntime? = null

  override fun createHostValidator(): HostValidator =
    if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
      HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
    } else {
      HostValidator.Builder(this)
        .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
        .build()
    }

  override fun onCreateSession(sessionInfo: SessionInfo): Session {
    val shared = runtime ?: createMapRuntime(MapRuntimeOptions()).also { runtime = it }
    return MapCarSession(shared)
  }

  override fun onDestroy() {
    try {
      // CarAppService destroys session and screen lifecycles, releasing their Surfaces first.
      super.onDestroy()
    } finally {
      runtime?.close()
      runtime = null
    }
  }
}

private class MapCarSession(private val runtime: MapRuntime) : Session() {
  private var mapScreen: PlacesScreen? = null

  override fun onCreateScreen(intent: Intent): Screen =
    PlacesScreen(carContext, runtime).also { mapScreen = it }

  override fun onCarConfigurationChanged(newConfiguration: Configuration) {
    mapScreen?.updateConfiguration(newConfiguration)
  }
}
