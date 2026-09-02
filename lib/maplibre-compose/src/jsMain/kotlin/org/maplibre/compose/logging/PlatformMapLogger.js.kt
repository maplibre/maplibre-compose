package org.maplibre.compose.logging

internal actual fun platformMapLogger(): MapLogger = MapLogger { record ->
  val line = record.toPlatformLine()
  val throwable = record.throwable
  when (record.level) {
    MapLogLevel.Debug -> if (throwable == null) console.log(line) else console.log(line, throwable)
    MapLogLevel.Info -> if (throwable == null) console.info(line) else console.info(line, throwable)
    MapLogLevel.Warning ->
      if (throwable == null) console.warn(line) else console.warn(line, throwable)
    MapLogLevel.Error ->
      if (throwable == null) console.error(line) else console.error(line, throwable)
  }
}
