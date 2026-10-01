package org.maplibre.compose.mlnffi

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.maplibre.nativeffi.runtime.NetworkStatus

class SharedNetworkMonitorTest {
  private val callbacks = mutableListOf<(NetworkStatus) -> Unit>()
  private val statuses = mutableListOf<NetworkStatus>()
  private var stops = 0
  private val monitor =
    SharedNetworkMonitor(
      startMonitor = { callback ->
        callbacks += callback
        AutoCloseable { stops++ }
      },
      setStatus = statuses::add,
    )

  @Test
  fun runtimes_share_a_monitor_until_the_last_one_closes() {
    val first = monitor.acquire()
    val second = monitor.acquire()
    assertEquals(1, callbacks.size)
    callbacks.single()(NetworkStatus.OFFLINE)

    first.close()
    first.close()
    assertEquals(0, stops)
    assertEquals(listOf(NetworkStatus.OFFLINE), statuses)

    callbacks.single()(NetworkStatus.ONLINE)
    second.close()
    assertEquals(1, stops)
    assertEquals(NetworkStatus.ONLINE, statuses.last())
  }

  @Test
  fun a_stopped_monitor_cannot_change_status_after_restart() {
    monitor.acquire().close()
    monitor.acquire().use {
      callbacks.last()(NetworkStatus.OFFLINE)
      callbacks.first()(NetworkStatus.ONLINE)
      assertEquals(NetworkStatus.OFFLINE, statuses.last())
    }
    callbacks.last()(NetworkStatus.OFFLINE)
    assertEquals(NetworkStatus.ONLINE, statuses.last())
    assertEquals(2, stops)
  }

  @Test
  fun a_synchronous_initial_status_is_applied_during_startup() {
    val monitor =
      SharedNetworkMonitor(
        startMonitor = { callback ->
          callback(NetworkStatus.OFFLINE)
          AutoCloseable {}
        },
        setStatus = statuses::add,
      )
    monitor.acquire().use { assertEquals(listOf(NetworkStatus.OFFLINE), statuses) }
    assertEquals(NetworkStatus.ONLINE, statuses.last())
  }

  @Test
  fun failed_startup_restores_online_and_does_not_retain_a_lease() {
    var starts = 0
    val monitor =
      SharedNetworkMonitor(
        startMonitor = { callback ->
          starts++
          callback(NetworkStatus.OFFLINE)
          if (starts == 1) error("start failed")
          AutoCloseable { stops++ }
        },
        setStatus = statuses::add,
      )
    assertFailsWith<IllegalStateException> { monitor.acquire() }
    assertEquals(NetworkStatus.ONLINE, statuses.last())
    monitor.acquire().use { assertEquals(NetworkStatus.OFFLINE, statuses.last()) }
    assertEquals(2, starts)
    assertEquals(1, stops)
  }
}
