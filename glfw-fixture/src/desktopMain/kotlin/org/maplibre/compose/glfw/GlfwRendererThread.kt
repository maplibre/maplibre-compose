package org.maplibre.compose.glfw

import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/**
 * The thread this host serializes its graphics work on.
 *
 * MapLibre's runtime binds to whichever thread first reaches it, so a host must offer one
 * consistent thread through `withRendererAccess`. It cannot be the GLFW main thread: MapLibre's
 * Metal backend commits its command buffer and waits on it inside `renderUpdate`, which would block
 * event pumping on the GPU.
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
   * Runs [action] on this thread and waits for it. Must stay re-entrant: a camera mutation requests
   * a frame while already on this thread, and a nested submit would deadlock the executor.
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
