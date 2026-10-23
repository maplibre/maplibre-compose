package org.maplibre.compose.mlnffi

import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest

class MacosNetworkMonitorTest {
  @Test
  fun the_system_monitor_delivers_an_initial_status_and_can_restart() =
    runTest(timeout = 30.seconds) {
      if (!System.getProperty("os.name").lowercase().contains("mac")) return@runTest
      repeat(3) {
        val received = CompletableDeferred<Unit>()
        MacosNetworkMonitor { received.complete(Unit) }.use { received.await() }
      }
    }
}
