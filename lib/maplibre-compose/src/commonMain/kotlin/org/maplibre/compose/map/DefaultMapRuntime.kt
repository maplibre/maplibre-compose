package org.maplibre.compose.map

import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock

/**
 * The process-default [MapRuntime].
 *
 * [instance] creates the runtime on first access with the options from [configure], or the
 * [MapRuntimeOptions] defaults when nothing configured it. Closing the runtime permanently closes
 * the process default; later reads return the same closed runtime.
 */
public object DefaultMapRuntime {
  private val lock = reentrantLock()
  private var options: MapRuntimeOptions? = null
  private var current: MapRuntime? = null

  /**
   * Sets the options [instance] uses when it creates the runtime.
   *
   * Call this before the first map, snapshotter, or offline manager, such as from `Application`
   * creation or `main`. Throws [IllegalStateException] once the runtime exists.
   */
  public fun configure(options: MapRuntimeOptions): Unit = lock.withLock {
    check(current == null) {
      "The default map runtime already exists; configure it before the first use"
    }
    this.options = options
  }

  /** The process-default runtime, created on first access. */
  public val instance: MapRuntime
    get() = lock.withLock {
      current ?: createMapRuntime(options ?: defaultMapRuntimeOptions()).also { current = it }
    }

  /** Forgets and closes the process default, returning it so a test can await closure. */
  internal fun clearForTest(): MapRuntime? =
    lock
      .withLock {
        options = null
        current.also { current = null }
      }
      ?.also { it.close() }
}
