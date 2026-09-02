package org.maplibre.compose.logging

internal actual fun platformMapLogger(): MapLogger = MapLogger { record ->
  val line = record.toPlatformLine()
  val args = if (record.throwable == null) arrayOf<Any?>(line) else arrayOf(line, record.throwable)
  when (record.level) {
    MapLogLevel.Debug -> console.log(*args)
    MapLogLevel.Info -> console.info(*args)
    MapLogLevel.Warning -> console.warn(*args)
    MapLogLevel.Error -> console.error(*args)
  }
}
