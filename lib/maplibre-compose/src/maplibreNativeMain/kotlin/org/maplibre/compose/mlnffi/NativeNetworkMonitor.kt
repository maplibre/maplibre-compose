package org.maplibre.compose.mlnffi

import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import org.maplibre.compose.resource.ConnectivityMode
import org.maplibre.compose.resource.UnspecifiedConnectivityMode
import org.maplibre.nativeffi.runtime.NetworkStatus

/** Starts observing connectivity until the returned handle is closed. */
internal expect fun startNativeNetworkMonitor(onStatus: (NetworkStatus) -> Unit): AutoCloseable

/** Combines the application override with one monitor shared by all native runtimes. */
internal class SharedNetworkMonitor(
  private val startMonitor: ((NetworkStatus) -> Unit) -> AutoCloseable,
  private val setStatus: (NetworkStatus) -> Unit,
) {
  private class Session(val identity: Any) {
    lateinit var monitor: AutoCloseable
    var status: NetworkStatus? = null
  }

  private val lock = reentrantLock()
  private var session: Session? = null
  private var users = 0
  private var currentMode: ConnectivityMode = ConnectivityMode.Automatic
  private var appliedStatus: NetworkStatus? = null

  var mode: ConnectivityMode
    get() = lock.withLock { currentMode }
    set(value) = lock.withLock {
      check(value != UnspecifiedConnectivityMode) { "UnspecifiedConnectivityMode is never used" }
      currentMode = value
      // Configuration before the first runtime must not load Native or start an OS monitor.
      if (session != null) publishStatus()
    }

  /** Called under [lock], including during startup and after the last session is retired. */
  private fun publishStatus() {
    val status =
      when (currentMode) {
        ConnectivityMode.ForceOnline -> NetworkStatus.ONLINE
        ConnectivityMode.ForceOffline -> NetworkStatus.OFFLINE
        ConnectivityMode.Automatic -> session?.status ?: NetworkStatus.ONLINE
        UnspecifiedConnectivityMode -> error("UnspecifiedConnectivityMode is never used")
      }
    if (status != appliedStatus) {
      setStatus(status)
      appliedStatus = status
    }
  }

  fun acquire(): AutoCloseable = lock.withLock {
    if (users == 0) {
      val identity = Any()
      val starting = Session(identity)
      session = starting
      try {
        publishStatus()
        starting.monitor = startMonitor { status ->
          lock.withLock {
            val active = session
            if (active?.identity === identity) {
              active.status = status
              publishStatus()
            }
          }
        }
      } catch (error: Throwable) {
        session = null
        publishStatus()
        throw error
      }
    }
    users++
    var released = false
    AutoCloseable {
      val stopping = lock.withLock {
        if (released) return@AutoCloseable
        released = true
        users--
        if (users == 0) {
          val stopping = session
          session = null
          // Discard the old observation, while keeping the application's explicit override.
          publishStatus()
          stopping?.monitor
        } else {
          null
        }
      }
      // Cancellation may wait for an OS callback, which needs the lock above.
      stopping?.close()
    }
  }
}
