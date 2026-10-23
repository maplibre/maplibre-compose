package org.maplibre.compose.map

import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Suspends until [condition], checked on main, holds. It is checked again after every write to
 * Compose state, every applied snapshot, and every emission of [changes], so it may read Compose
 * state and whatever [changes] reports on.
 *
 * Global writes wake it even while no composition sends apply notifications, as after a presenter
 * closes.
 */
internal suspend fun awaitOnMain(
  description: String,
  vararg changes: Flow<*>,
  timeoutMillis: Long = 10_000L,
  condition: () -> Boolean,
) {
  val wake = Channel<Unit>(Channel.CONFLATED)
  val writes = Snapshot.registerGlobalWriteObserver { wake.trySend(Unit) }
  val applies = Snapshot.registerApplyObserver { _, _ -> wake.trySend(Unit) }
  try {
    withTimeout(timeoutMillis) {
      coroutineScope {
        for (flow in changes) launch { flow.collect { wake.trySend(Unit) } }
        withContext(Dispatchers.Main) { while (!condition()) wake.receive() }
        coroutineContext.cancelChildren()
      }
    }
  } catch (error: TimeoutCancellationException) {
    throw AssertionError("Timed out waiting for $description", error)
  } finally {
    writes.dispose()
    applies.dispose()
  }
}
