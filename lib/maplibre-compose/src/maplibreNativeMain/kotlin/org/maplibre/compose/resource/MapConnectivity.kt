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
   * `false` forces offline mode; maps can use offline packs and usable cached resources. `true`
   * permits network requests regardless of the operating system's reported connectivity. `null`,
   * the default, follows the operating system on Android, iOS, and macOS, and permits requests on
   * other platforms. Requests are also permitted until the operating system reports its initial
   * connectivity.
   *
   * The operating system is still observed while an override is set. Clearing the override applies
   * its latest report. Restoring online connectivity retries pending connection-failed requests.
   * Already-running requests may finish, and application [MapResourceProvider] implementations
   * control their own network access. Cached resources keep MapLibre's cache freshness rules.
   *
   * May be read or changed from any thread, before or after creating a runtime. The value persists
   * after every runtime closes. Reading it returns the override, not actual network availability.
   */
  public var connectedOverride: Boolean?
    get() = monitor.connectedOverride
    set(value) {
      monitor.connectedOverride = value
    }

  internal fun acquireMonitor(): AutoCloseable = monitor.acquire()
}
