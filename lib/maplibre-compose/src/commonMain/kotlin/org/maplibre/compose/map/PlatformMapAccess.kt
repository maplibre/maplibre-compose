@file:OptIn(ExperimentalAtomicApi::class)

package org.maplibre.compose.map

import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlinx.coroutines.CancellableContinuation

/** Marks direct platform-map access with a callback-scoped lifetime. */
@RequiresOptIn(
  message =
    "The platform map is borrowed only for the withPlatformMap callback. " +
      "Do not retain it or run long work in the callback.",
  level = RequiresOptIn.Level.WARNING,
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
public annotation class DelicateMapApi

/** Provides callback-scoped access to the current platform engine map. */
@DelicateMapApi public expect class PlatformMapScope

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
 * @throws IllegalStateException if the map is already closed. Web also throws this exception if no
 *   surface is attached when the call starts.
 * @throws kotlinx.coroutines.CancellationException if the map, engine, or Web attachment changes
 *   before [block] starts.
 */
@DelicateMapApi
public expect suspend fun <T> MapState.withPlatformMap(block: PlatformMapScope.() -> T): T

/** Arbitrates cancellation against the start of one queued platform-map action. */
internal class PlatformMapInvocation<T>(private val continuation: CancellableContinuation<T>) {
  private val state = AtomicReference(PlatformMapInvocationState.QUEUED)

  val isQueued: Boolean
    get() = state.load() == PlatformMapInvocationState.QUEUED

  fun cancel() {
    state.compareAndSet(PlatformMapInvocationState.QUEUED, PlatformMapInvocationState.CANCELLED)
  }

  fun execute(block: () -> T) {
    if (
      !state.compareAndSet(PlatformMapInvocationState.QUEUED, PlatformMapInvocationState.RUNNING)
    ) {
      return
    }
    val result = runCatching(block)
    if (continuation.isActive) continuation.resumeWith(result)
  }

  fun fail(error: Throwable) {
    execute { throw error }
  }
}

private enum class PlatformMapInvocationState {
  QUEUED,
  RUNNING,
  CANCELLED,
}
