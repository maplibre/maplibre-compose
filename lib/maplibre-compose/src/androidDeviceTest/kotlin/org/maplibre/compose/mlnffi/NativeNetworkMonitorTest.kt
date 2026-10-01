package org.maplibre.compose.mlnffi

import android.net.Network
import android.os.Parcel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.maplibre.nativeffi.runtime.NetworkStatus

class NativeNetworkMonitorTest {
  @Test
  fun a_network_handover_ignores_loss_of_the_previous_default() {
    val statuses = mutableListOf<NetworkStatus>()
    val callback = DefaultNetworkCallback(statuses::add)
    val first = network(1)
    val second = network(2)
    callback.initialize(null)
    callback.onAvailable(first)
    callback.onAvailable(second)
    callback.onLost(first)
    assertEquals(NetworkStatus.ONLINE, statuses.last())
    callback.onLost(second)
    assertEquals(
      listOf(
        NetworkStatus.OFFLINE,
        NetworkStatus.ONLINE,
        NetworkStatus.ONLINE,
        NetworkStatus.OFFLINE,
      ),
      statuses,
    )
  }

  @Test
  fun the_system_monitor_delivers_an_initial_status_and_can_restart() {
    AndroidMlnFfiPlatform.initialize()
    repeat(3) {
      val received = TestLatch(1)
      startNativeNetworkMonitor { received.countDown() }
        .use {
          assertTrue(received.await(10_000), "No initial default network status was delivered")
        }
    }
  }

  private fun network(id: Int): Network {
    val parcel = Parcel.obtain()
    return try {
      parcel.writeInt(id)
      parcel.setDataPosition(0)
      Network.CREATOR.createFromParcel(parcel)
    } finally {
      parcel.recycle()
    }
  }
}
