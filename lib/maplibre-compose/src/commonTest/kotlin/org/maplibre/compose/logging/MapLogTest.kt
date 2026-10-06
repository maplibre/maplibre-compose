package org.maplibre.compose.logging

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class MapLogTest {
  private val previous = MapLogging.logger

  @AfterTest
  fun restoreLogger() {
    MapLogging.logger = previous
  }

  @Test
  fun a_throwing_logger_drops_the_record_without_failing_the_caller() {
    var calls = 0
    MapLogging.logger = MapLogger {
      calls++
      error("logger exploded")
    }
    MapLog.w { "first" }
    MapLog.log(MapLogLevel.Error, null, { "engine" }, MapLogSource.NativeEngine, "Style")
    assertEquals(2, calls)
  }

  @Test
  fun a_throwing_min_level_drops_the_record_without_failing_the_caller() {
    var calls = 0
    MapLogging.logger =
      object : MapLogger {
        override val minLevel: MapLogLevel
          get() = error("minLevel exploded")

        override fun log(record: MapLogRecord) {
          calls++
        }
      }
    MapLog.e { "dropped" }
    assertEquals(0, calls)
  }
}
