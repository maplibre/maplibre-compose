package org.maplibre.compose.map

internal actual val mlnFfiOperatingSystem: String
  get() = System.getProperty("os.name") ?: "unknown"

internal actual val mlnFfiArchitecture: String
  get() = System.getProperty("os.arch") ?: "unknown"
