package org.maplibre.compose.mlnffi

import kotlin.test.Test
import kotlin.test.assertTrue

class NativeNetworkMonitorTest {
  @Test
  fun the_system_monitor_delivers_an_initial_status_and_can_restart() {
    repeat(3) {
      val received = TestLatch(1)
      startNativeNetworkMonitor { received.countDown() }
        .use {
          assertTrue(received.await(10_000), "No initial network path was delivered")
        }
    }
  }
}
