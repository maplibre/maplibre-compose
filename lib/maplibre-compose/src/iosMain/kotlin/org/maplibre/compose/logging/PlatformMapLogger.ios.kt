package org.maplibre.compose.logging

import platform.Foundation.NSLog

internal actual fun platformMapLogger(): MapLogger = MapLogger { record ->
  val trace = record.throwable?.let { "\n" + it.stackTraceToString() }.orEmpty()
  NSLog("%s", "[${record.level}] ${record.toPlatformLine()}$trace")
}
