package org.maplibre.compose.mlnffi

import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import org.maplibre.nativeffi.Maplibre
import org.maplibre.nativeffi.runtime.NetworkStatus

/** Starts observing connectivity until the returned handle is closed. */
internal expect fun startNativeNetworkMonitor(onStatus: (NetworkStatus) -> Unit): AutoCloseable

/** Network status belongs to the process, including its map, snapshot, and offline runtimes. */
internal val nativeNetworkMonitor =
  SharedNetworkMonitor(::startNativeNetworkMonitor, Maplibre::setNetworkStatus)

/** Keeps one platform monitor alive until the last native runtime has finished teardown. */
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

  fun acquire(): AutoCloseable = lock.withLock {
    if (users == 0) {
      val identity = Any()
      val starting = Session(identity)
      session = starting
      try {
        starting.monitor = startMonitor { status ->
          lock.withLock {
            val active = session
            if (active?.identity === identity) {
              active.status = status
              setStatus(status)
            }
          }
        }
      } catch (error: Throwable) {
        session = null
        setStatus(NetworkStatus.ONLINE)
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
          // A stopped monitor must not leave future runtimes permanently offline.
          if (stopping?.status == NetworkStatus.OFFLINE) setStatus(NetworkStatus.ONLINE)
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
