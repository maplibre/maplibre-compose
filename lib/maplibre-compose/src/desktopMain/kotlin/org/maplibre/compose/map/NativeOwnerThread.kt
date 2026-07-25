package org.maplibre.compose.map

import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/**
 * The single thread that owns one map's native handles.
 *
 * MapLibre Native binds a runtime to its creating thread and rejects calls from anywhere else, so
 * every runtime, map, and render-session call is funnelled through here. The thread must be
 * dedicated: the native layer refuses to create a runtime on a thread that already has one, or that
 * already carries a MapLibre scheduler, which rules out pooled dispatcher threads, the AWT event
 * thread, and Skiko's render thread.
 */
internal class NativeOwnerThread(name: String) : AutoCloseable {
  private val threadRef = AtomicReference<Thread?>()

  private val executor = Executors.newSingleThreadExecutor { task ->
    Thread(task, name).also {
      it.isDaemon = true
      threadRef.set(it)
    }
  }

  /** Whether the calling thread is the owner thread. */
  val isOwnerThread: Boolean
    get() = Thread.currentThread() === threadRef.get()

  /**
   * Runs [action] on the owner thread and waits for it.
   *
   * Re-entrant: called from the owner thread, it runs [action] directly. Without that, any nested
   * hop — and camera mutators nest, because each one requests a frame — would deadlock against the
   * single-threaded executor.
   */
  fun <T> run(action: () -> T): T {
    if (isOwnerThread) return action()
    return try {
      executor.submit<T>(action).get()
    } catch (error: ExecutionException) {
      throw error.cause ?: error
    }
  }

  /** Asserts the caller is the owner thread, naming [operation] if it is not. */
  fun assertOwnerThread(operation: String) {
    check(isOwnerThread) {
      "$operation must run on the map's owner thread, but ran on ${Thread.currentThread().name}. " +
        "MapLibre Native enforces this natively, so the failure would otherwise surface as a " +
        "WrongThreadException pointing at the FFI boundary rather than at this caller."
    }
  }

  override fun close() {
    executor.shutdown()
  }
}
