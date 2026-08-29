package org.maplibre.compose.map

import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CoroutineDispatcher

/**
 * Chooses [main] when that dispatcher can accept work, and [fallback] when accessing it throws or
 * when a probe of [CoroutineDispatcher.isDispatchNeeded] throws. A missing Compose Main on desktop
 * is a sentinel that fails only when used; the probe selects the fallback before the first commit.
 */
internal fun resolveHostDispatcher(
  main: () -> CoroutineDispatcher,
  fallback: () -> CoroutineDispatcher,
): CoroutineDispatcher {
  val candidate = runCatching {
    main()
  }
    .getOrElse {
      return fallback()
    }
  val usable = runCatching { candidate.isDispatchNeeded(EmptyCoroutineContext) }.isSuccess
  return if (usable) candidate else fallback()
}

/** The dispatcher [MapState] commits on when the caller does not supply one. */
internal expect fun defaultHostDispatcher(): CoroutineDispatcher

/**
 * True when [dispatcher] only runs work after an external test scheduler advances it. A [MapState]
 * test drives commits from the test thread, so those writes stay inline.
 */
internal fun isVirtualTestDispatcher(dispatcher: CoroutineDispatcher): Boolean =
  dispatcher::class.simpleName?.contains("TestDispatcher") == true

/** Runs [block] on [dispatcher] and returns its result. */
internal expect fun <T> runBlockingOn(dispatcher: CoroutineDispatcher, block: () -> T): T
