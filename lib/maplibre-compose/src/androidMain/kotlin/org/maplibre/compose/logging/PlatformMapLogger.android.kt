package org.maplibre.compose.logging

import android.util.Log

internal actual fun platformMapLogger(): MapLogger = MapLogger { record ->
  val priority =
    when {
      record.level >= MapLogLevel.Error -> Log.ERROR
      record.level >= MapLogLevel.Warning -> Log.WARN
      record.level >= MapLogLevel.Info -> Log.INFO
      else -> Log.DEBUG
    }
  val trace = record.throwable?.let { "\n" + Log.getStackTraceString(it) }.orEmpty()
  Log.println(priority, MapLogTag, record.categorizedMessage() + trace)
}
