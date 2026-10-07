package org.maplibre.compose.resource

import org.maplibre.compose.mlnffi.SharedNetworkMonitor
import org.maplibre.compose.mlnffi.startNativeNetworkMonitor
import org.maplibre.nativeffi.Maplibre

/** Controls network connectivity for all MapLibre Native map runtimes in this process. */
public object MapConnectivity {
  private val monitor =
    SharedNetworkMonitor(::startNativeNetworkMonitor, Maplibre::setNetworkStatus)

  /**
   * Connectivity mode for maps, snapshots, and offline downloads.
   *
   * Defaults to [ConnectivityMode.Automatic]. May be changed from any thread, before or after
   * creating a runtime, and persists after runtimes close.
   *
   * Existing network requests may finish. Application [MapResourceProvider] implementations control
   * their own networking.
   */
  public var mode: ConnectivityMode
    get() = monitor.mode
    set(value) {
      monitor.mode = value
    }

  internal fun acquireMonitor(): AutoCloseable = monitor.acquire()
}
