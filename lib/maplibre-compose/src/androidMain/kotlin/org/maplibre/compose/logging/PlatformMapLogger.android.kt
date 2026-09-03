package org.maplibre.compose.logging

import android.util.Log

internal actual fun platformMapLogger(): MapLogger = MapLogger { record ->
  val priority =
    when (record.level) {
      MapLogLevel.Debug -> Log.DEBUG
      MapLogLevel.Info -> Log.INFO
      MapLogLevel.Warning -> Log.WARN
      MapLogLevel.Error -> Log.ERROR
    }
  val trace = record.throwable?.let { "\n" + Log.getStackTraceString(it) }.orEmpty()
  Log.println(priority, MAP_LOG_TAG, record.categorizedMessage() + trace)
}
