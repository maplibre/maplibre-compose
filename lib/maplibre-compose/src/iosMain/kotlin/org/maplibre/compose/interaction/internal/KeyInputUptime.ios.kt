package org.maplibre.compose.interaction.internal

import platform.Foundation.NSProcessInfo

internal actual fun keyDispatchUptimeMillis(): Long =
  (NSProcessInfo.processInfo.systemUptime * 1000).toLong()
