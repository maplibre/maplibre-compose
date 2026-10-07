@file:OptIn(ExperimentalAtomicApi::class)

package org.maplibre.compose.map

import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import org.maplibre.compose.util.DelicateMaplibreComposeApi
import org.maplibre.compose.util.ExperimentalMaplibreComposeApi

/** Provides callback-scoped access to the current platform engine map. */
@DelicateMaplibreComposeApi @ExperimentalMaplibreComposeApi public expect class PlatformMapScope

/**
 * Runs [block] on this logical map's engine owner context.
 *
 * The platform map is borrowed for [block]. Use the raw handle only during [block]. Kotlin cannot
 * prevent retention. A long-running block stops the map from processing other work.
 *
 * Native platforms create the engine map when necessary and permit access without an attached UI
 * surface. Web requires an attached surface. Cancelling while the invocation is queued prevents
 * [block] from running. Once execution starts, cancellation does not interrupt [block], but its
 * result is discarded.
 *
 * The map is a maplibre-native-ffi `MapHandle` on Native platforms and a MapLibre GL JS `Map` on
 * web. Those engine types may change in any minor release, so this function also requires opt-in to
 * [ExperimentalMaplibreComposeApi].
 *
 * @throws IllegalStateException if the map is already closed. Web also throws this exception if no
 *   surface is attached when the call starts.
 * @throws kotlinx.coroutines.CancellationException if the map, engine, or Web attachment changes
 *   before [block] starts.
 */
@DelicateMaplibreComposeApi
@ExperimentalMaplibreComposeApi
public expect suspend fun <T> MapState.withPlatformMap(block: PlatformMapScope.() -> T): T

/** Arbitrates cancellation against the start of one queued platform-map action. */
internal class PlatformMapInvocation<T>(private val continuation: CancellableContinuation<T>) {
  private val state = AtomicReference(PlatformMapInvocationState.Queued)

  val isQueued: Boolean
    get() = state.load() == PlatformMapInvocationState.Queued

  fun cancel() {
    state.compareAndSet(PlatformMapInvocationState.Queued, PlatformMapInvocationState.Cancelled)
  }

  fun execute(block: () -> T) {
    if (
      !state.compareAndSet(PlatformMapInvocationState.Queued, PlatformMapInvocationState.Running)
    ) {
      return
    }
    val result = runCatching(block)
    if (continuation.isActive) continuation.resumeWith(result)
  }

  fun fail(error: Throwable) {
    execute { throw error }
  }

  /**
   * Runs [block] inside [lifecycleGate] and then [authorityGate]. A gate returns false when the map
   * changed after this invocation was queued, which fails it with [changedMessage].
   */
  fun executeGated(
    changedMessage: String,
    lifecycleGate: (() -> Unit) -> Boolean,
    authorityGate: (() -> Unit) -> Boolean,
    block: () -> T,
  ) {
    execute {
      var result: Result<T>? = null
      val lifecycleAccepted = lifecycleGate {
        val authorityAccepted = authorityGate { result = runCatching(block) }
        if (!authorityAccepted) throw CancellationException(changedMessage)
      }
      if (!lifecycleAccepted) throw CancellationException(changedMessage)
      checkNotNull(result).getOrThrow()
    }
  }
}

private enum class PlatformMapInvocationState {
  Queued,
  Running,
  Cancelled,
}
