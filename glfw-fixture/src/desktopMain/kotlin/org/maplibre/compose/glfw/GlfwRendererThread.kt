package org.maplibre.compose.glfw

import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/**
 * The thread this host serializes its graphics work on.
 *
 * A second copy of the default host's `HostRendererThread`, and the second thing the SPI does not
 * hand a host: MapLibre's runtime binds to whichever thread first reaches it, so every host has to
 * offer one consistent thread through `withRendererAccess` and keep offering it. That requirement
 * is stated in the SPI documentation but not supplied by it.
 *
 * It could not simply be the GLFW main thread. MapLibre's Metal backend commits its command buffer
 * and waits on it from inside `renderUpdate`, so running the map's frame on the thread GLFW pumps
 * events from would block window dragging on the GPU — and the map's own runtime loop already lives
 * on a thread of its own, so nothing is gained by folding this one into the UI thread.
 */
internal class GlfwRendererThread(name: String) : AutoCloseable {
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
