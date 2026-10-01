package org.maplibre.compose.mlnffi

import org.maplibre.nativeffi.runtime.NetworkStatus

internal actual fun startNativeNetworkMonitor(onStatus: (NetworkStatus) -> Unit): AutoCloseable =
  if (System.getProperty("os.name").lowercase().contains("mac")) MacosNetworkMonitor(onStatus)
  else AutoCloseable {}
