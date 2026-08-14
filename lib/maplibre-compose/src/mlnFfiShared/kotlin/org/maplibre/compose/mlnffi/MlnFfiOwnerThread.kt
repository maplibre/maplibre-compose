package org.maplibre.compose.mlnffi

/**
 * One dedicated thread that runs [body] to completion and then exits.
 *
 * MapLibre binds a runtime to the thread that created it and allows one runtime per thread, so a
 * loop that owns a runtime owns a thread with it. This starts one OS thread that carries [name],
 * runs [body] exactly once, and never migrates to another thread or returns to a pool.
 *
 * The thread is a daemon: a parked native pump ignores interruption, so a host that exits while
 * [body] is still running leaves the thread behind rather than waiting for it. A caller starts the
 * thread once and joins it once.
 */
internal class MlnFfiOwnerThread(name: String, body: () -> Unit) {
  private val delegate = Thread(body, name).apply { isDaemon = true }

  /** Starts the thread. Called once. */
  fun start() {
    delegate.start()
  }

  /** Whether the calling thread is this one. */
  fun isCurrent(): Boolean = Thread.currentThread() === delegate

  /**
   * Waits up to [timeoutMillis] for [body] to return, reporting whether it did.
   *
   * A false result leaves the thread running, and the caller reports the wedged loop rather than
   * waiting again.
   */
  fun join(timeoutMillis: Long): Boolean =
    try {
      delegate.join(timeoutMillis)
      !delegate.isAlive
    } catch (interruption: InterruptedException) {
      Thread.currentThread().interrupt()
      !delegate.isAlive
    }
}

/** The name of the calling thread, for diagnostics. */
internal fun currentMlnFfiThreadName(): String = Thread.currentThread().name
