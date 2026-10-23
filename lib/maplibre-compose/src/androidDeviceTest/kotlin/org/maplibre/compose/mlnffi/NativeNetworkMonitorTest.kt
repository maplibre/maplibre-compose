package org.maplibre.compose.mlnffi

import android.net.Network
import android.os.Parcel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.maplibre.nativeffi.generated.NetworkStatus

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
  fun the_system_monitor_delivers_an_initial_status_and_can_restart() =
    runTest(timeout = 30.seconds) {
      AndroidMlnFfiPlatform.initialize()
      repeat(3) {
        val received = CompletableDeferred<Unit>()
        startNativeNetworkMonitor { received.complete(Unit) }.use { received.await() }
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
