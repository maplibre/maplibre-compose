package org.maplibre.compose.mlnffi

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
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
    assertEquals(listOf(NetworkStatus.ONLINE, NetworkStatus.OFFLINE), statuses)

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
    monitor.acquire().use {
      assertEquals(listOf(NetworkStatus.ONLINE, NetworkStatus.OFFLINE), statuses)
    }
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

  @Test
  fun an_override_before_startup_is_stored_without_starting_native_services() {
    assertNull(monitor.connectedOverride)
    monitor.connectedOverride = false
    assertEquals(false, monitor.connectedOverride)
    assertTrue(callbacks.isEmpty())
    assertTrue(statuses.isEmpty())

    monitor.acquire().use {
      assertEquals(listOf(NetworkStatus.OFFLINE), statuses)
      callbacks.single()(NetworkStatus.ONLINE)
      assertEquals(listOf(NetworkStatus.OFFLINE), statuses)
    }
    assertEquals(false, monitor.connectedOverride)
    assertEquals(listOf(NetworkStatus.OFFLINE), statuses)
  }

  @Test
  fun clearing_an_override_applies_the_latest_observation_in_both_directions() {
    monitor.acquire().use {
      monitor.connectedOverride = false
      callbacks.single()(NetworkStatus.OFFLINE)
      callbacks.single()(NetworkStatus.ONLINE)
      assertEquals(NetworkStatus.OFFLINE, statuses.last())
      monitor.connectedOverride = null
      assertEquals(NetworkStatus.ONLINE, statuses.last())

      monitor.connectedOverride = true
      callbacks.single()(NetworkStatus.OFFLINE)
      assertEquals(NetworkStatus.ONLINE, statuses.last())
      monitor.connectedOverride = null
      assertEquals(NetworkStatus.OFFLINE, statuses.last())
      assertNull(monitor.connectedOverride)
    }
  }

  @Test
  fun an_override_survives_restart_and_stale_callbacks() {
    monitor.acquire().use {
      callbacks.single()(NetworkStatus.OFFLINE)
      monitor.connectedOverride = false
    }
    assertEquals(NetworkStatus.OFFLINE, statuses.last())
    monitor.acquire().use {
      callbacks.last()(NetworkStatus.ONLINE)
      callbacks.first()(NetworkStatus.OFFLINE)
      monitor.connectedOverride = null
      assertEquals(NetworkStatus.ONLINE, statuses.last())
    }
    callbacks.last()(NetworkStatus.OFFLINE)
    assertEquals(NetworkStatus.ONLINE, statuses.last())
  }

  @Test
  fun restart_discards_an_old_offline_observation() {
    monitor.acquire().use {
      callbacks.single()(NetworkStatus.OFFLINE)
      monitor.connectedOverride = false
    }
    monitor.connectedOverride = null
    monitor.acquire().use { assertEquals(NetworkStatus.ONLINE, statuses.last()) }
  }

  @Test
  fun repeated_effective_statuses_are_not_published() {
    monitor.acquire().use {
      callbacks.single()(NetworkStatus.ONLINE)
      monitor.connectedOverride = true
      monitor.connectedOverride = true
      monitor.connectedOverride = null
      assertEquals(listOf(NetworkStatus.ONLINE), statuses)

      monitor.connectedOverride = false
      callbacks.single()(NetworkStatus.OFFLINE)
      monitor.connectedOverride = null
      assertEquals(listOf(NetworkStatus.ONLINE, NetworkStatus.OFFLINE), statuses)
    }
  }

  @Test
  fun failed_startup_preserves_the_override_and_allows_a_later_lease() {
    var starts = 0
    val monitor =
      SharedNetworkMonitor(
        startMonitor = { callback ->
          starts++
          callback(NetworkStatus.ONLINE)
          if (starts == 1) error("start failed")
          AutoCloseable { stops++ }
        },
        setStatus = statuses::add,
      )
    monitor.connectedOverride = false
    assertFailsWith<IllegalStateException> { monitor.acquire() }
    assertEquals(false, monitor.connectedOverride)
    assertEquals(listOf(NetworkStatus.OFFLINE), statuses)
    monitor.acquire().use {
      monitor.connectedOverride = null
      assertEquals(NetworkStatus.ONLINE, statuses.last())
    }
    assertEquals(1, stops)
  }
}
