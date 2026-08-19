package org.maplibre.compose.map

import android.os.Build

internal actual val mlnFfiOperatingSystem: String = "Android ${Build.VERSION.SDK_INT}"

internal actual val mlnFfiArchitecture: String
  get() = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"
