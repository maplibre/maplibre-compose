package org.maplibre.compose.resource

import org.maplibre.compose.mlnffi.SharedNetworkMonitor
import org.maplibre.compose.mlnffi.startNativeNetworkMonitor
import org.maplibre.nativeffi.Maplibre

/** Controls network connectivity for all MapLibre Native map runtimes in this process. */
public object MapConnectivity {
  private val monitor =
    SharedNetworkMonitor(::startNativeNetworkMonitor, Maplibre::setNetworkStatus)

  /**
   * Overrides the connectivity that MapLibre uses for maps, snapshots, and offline downloads.
   *
   * [ConnectivityMode.ForceOffline] forces offline mode; maps can use offline packs and usable
   * cached resources. [ConnectivityMode.ForceOnline] permits network requests regardless of the
   * operating system's reported connectivity. [ConnectivityMode.Automatic], the default, follows
   * the operating system on Android, iOS, and macOS, and permits requests on other platforms.
   * Requests are also permitted until the operating system reports its initial connectivity.
   *
   * The operating system is still observed while an override is set. Switching to
   * [ConnectivityMode.Automatic] applies its latest report. Restoring online connectivity retries
   * pending connection-failed requests. Already-running requests may finish, and application
   * [MapResourceProvider] implementations control their own network access. Cached resources keep
   * MapLibre's cache freshness rules.
   *
   * May be read or changed from any thread, before or after creating a runtime. The value persists
   * after every runtime closes. Reading it returns the configured mode, not actual network
   * availability.
   */
  public var mode: ConnectivityMode
    get() = monitor.mode
    set(value) {
      monitor.mode = value
    }

  internal fun acquireMonitor(): AutoCloseable = monitor.acquire()
}
