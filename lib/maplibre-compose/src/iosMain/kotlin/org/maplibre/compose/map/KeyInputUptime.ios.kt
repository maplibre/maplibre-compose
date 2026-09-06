package org.maplibre.compose.map

import platform.Foundation.NSProcessInfo

internal actual fun keyDispatchUptimeMillis(): Long =
  (NSProcessInfo.processInfo.systemUptime * 1000).toLong()
