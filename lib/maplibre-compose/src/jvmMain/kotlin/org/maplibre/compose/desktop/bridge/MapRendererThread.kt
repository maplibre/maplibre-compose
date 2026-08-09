package org.maplibre.compose.desktop.bridge

import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/**
 * The thread a host serializes its graphics work on.
 *
 * MapLibre's runtime binds to whichever thread creates it, so the host has to offer one consistent
 * thread and keep offering it.
 */
internal class MapRendererThread(name: String) : AutoCloseable {
  private val threadRef = AtomicReference<Thread?>()

  private val executor = Executors.newSingleThreadExecutor { task ->
    Thread(task, name).also {
      it.isDaemon = true
      threadRef.set(it)
    }
  }

  /**
   * Runs [action] on this thread and waits for it.
   *
   * Re-entrant, which is load-bearing: a camera mutation requests a frame while already on this
   * thread, and a nested submit to a single-threaded executor would deadlock.
   */
  fun <T> run(action: () -> T): T {
    if (Thread.currentThread() === threadRef.get()) return action()
    return try {
      executor.submit<T>(action).get()
    } catch (error: ExecutionException) {
      throw error.cause ?: error
    }
  }

  override fun close() {
    executor.shutdown()
  }
}
