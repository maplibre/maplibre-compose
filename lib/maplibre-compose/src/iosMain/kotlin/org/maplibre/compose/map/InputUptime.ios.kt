package org.maplibre.compose.map

import platform.Foundation.NSProcessInfo

internal actual fun inputUptimeMillis(): Long =
  (NSProcessInfo.processInfo.systemUptime * 1000).toLong()
