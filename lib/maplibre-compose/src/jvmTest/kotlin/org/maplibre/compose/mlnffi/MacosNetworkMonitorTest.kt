package org.maplibre.compose.mlnffi

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertTrue

class MacosNetworkMonitorTest {
  @Test
  fun the_system_monitor_delivers_an_initial_status_and_can_restart() {
    if (!System.getProperty("os.name").lowercase().contains("mac")) return
    repeat(3) {
      val received = CountDownLatch(1)
      MacosNetworkMonitor { received.countDown() }
        .use {
          assertTrue(received.await(10, TimeUnit.SECONDS), "No initial network path was delivered")
        }
    }
  }
}
