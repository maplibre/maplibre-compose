package org.maplibre.compose.mlnffi

import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest

class NativeNetworkMonitorTest {
  @Test
  fun the_system_monitor_delivers_an_initial_status_and_can_restart() =
    runTest(timeout = 30.seconds) {
      repeat(3) {
        val received = CompletableDeferred<Unit>()
        startNativeNetworkMonitor { received.complete(Unit) }.use { received.await() }
      }
    }
}
