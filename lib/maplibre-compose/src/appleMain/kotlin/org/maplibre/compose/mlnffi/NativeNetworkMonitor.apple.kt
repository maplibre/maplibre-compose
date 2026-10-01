package org.maplibre.compose.mlnffi

import org.maplibre.nativeffi.runtime.NetworkStatus
import platform.Network.nw_path_get_status
import platform.Network.nw_path_monitor_cancel
import platform.Network.nw_path_monitor_create
import platform.Network.nw_path_monitor_set_queue
import platform.Network.nw_path_monitor_set_update_handler
import platform.Network.nw_path_monitor_start
import platform.Network.nw_path_status_unsatisfied
import platform.darwin.dispatch_queue_create

internal actual fun startNativeNetworkMonitor(onStatus: (NetworkStatus) -> Unit): AutoCloseable {
  val monitor = checkNotNull(nw_path_monitor_create())
  val queue = dispatch_queue_create("org.maplibre.compose.connectivity", null)
  nw_path_monitor_set_queue(monitor, queue)
  nw_path_monitor_set_update_handler(monitor) { path ->
    // A satisfiable path can connect on demand; allow the request to establish it.
    onStatus(
      if (nw_path_get_status(path) == nw_path_status_unsatisfied) NetworkStatus.OFFLINE
      else NetworkStatus.ONLINE
    )
  }
  nw_path_monitor_start(monitor)
  return AutoCloseable { nw_path_monitor_cancel(monitor) }
}
