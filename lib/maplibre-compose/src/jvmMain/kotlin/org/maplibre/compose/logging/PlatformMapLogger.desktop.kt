package org.maplibre.compose.logging

internal actual fun platformMapLogger(): MapLogger = MapLogger { record ->
  System.err.println("[${record.level}] ${record.toPlatformLine()}")
  record.throwable?.printStackTrace()
}
