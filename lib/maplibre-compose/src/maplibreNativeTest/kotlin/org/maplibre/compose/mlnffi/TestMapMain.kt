package org.maplibre.compose.mlnffi

import kotlin.concurrent.Volatile
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.newSingleThreadContext
import org.maplibre.compose.map.currentThreadIdentity

/**
 * The thread that every native map test runs its maps on, as an app runs a map on its UI thread.
 * Test bodies run on it too, so a test reads and writes map state directly, and engine callbacks
 * run whenever the test suspends.
 */
@OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
internal object TestMapMain {
  private val thread = newSingleThreadContext("maplibre-test-main")

  // Set by the first task that runs on the thread; until then, every caller is elsewhere.
  @Volatile private var threadIdentity: Any? = null

  /**
   * Runs work on the main thread, and runs it at once when the caller is already there, as
   * `Dispatchers.Main.immediate` does in an app.
   */
  val dispatcher: CoroutineDispatcher =
    object : CoroutineDispatcher() {
      override fun isDispatchNeeded(context: CoroutineContext): Boolean =
        threadIdentity == null || currentThreadIdentity() != threadIdentity

      override fun dispatch(context: CoroutineContext, block: Runnable) =
        thread.dispatch(
          context,
          Runnable {
            if (threadIdentity == null) threadIdentity = currentThreadIdentity()
            block.run()
          },
        )

      override fun toString(): String = "TestMapMain"
    }

  // Main only.
  private val cleanups = mutableListOf<suspend () -> Unit>()

  /** Runs [cleanup] when the current test body ends, after any cleanup deferred earlier. */
  fun deferCleanup(cleanup: suspend () -> Unit) {
    cleanups += cleanup
  }

  /** Runs every deferred cleanup, collecting their failures into the first. */
  suspend fun runCleanups() {
    var failure: Throwable? = null
    while (cleanups.isNotEmpty()) {
      val cleanup = cleanups.removeAt(0)
      try {
        cleanup()
      } catch (error: Throwable) {
        val first = failure
        if (first == null) failure = error else first.addSuppressed(error)
      }
    }
    failure?.let { throw it }
  }
}
