package org.maplibre.compose.mlnffi

import android.net.ConnectivityManager
import android.net.Network
import org.maplibre.nativeffi.runtime.NetworkStatus

internal actual fun startNativeNetworkMonitor(onStatus: (NetworkStatus) -> Unit): AutoCloseable {
  val manager =
    AndroidMlnFfiPlatform.applicationContext.getSystemService(ConnectivityManager::class.java)
  val callback = DefaultNetworkCallback(onStatus)
  synchronized(callback) {
    manager.registerDefaultNetworkCallback(callback)
    callback.initialize(manager.activeNetwork)
  }
  return AutoCloseable { manager.unregisterNetworkCallback(callback) }
}

/** A default network permits requests, including local servers and unvalidated networks. */
internal class DefaultNetworkCallback(private val onStatus: (NetworkStatus) -> Unit) :
  ConnectivityManager.NetworkCallback() {
  private var currentNetwork: Network? = null

  @Synchronized
  fun initialize(network: Network?) {
    currentNetwork = network
    onStatus(if (network == null) NetworkStatus.OFFLINE else NetworkStatus.ONLINE)
  }

  @Synchronized
  override fun onAvailable(network: Network) {
    currentNetwork = network
    onStatus(NetworkStatus.ONLINE)
  }

  @Synchronized
  override fun onLost(network: Network) {
    if (network != currentNetwork) return
    currentNetwork = null
    onStatus(NetworkStatus.OFFLINE)
  }
}
