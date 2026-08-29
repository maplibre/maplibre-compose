package org.maplibre.compose.map

import kotlinx.coroutines.CoroutineDispatcher

/**
 * Chooses [main] when that dispatcher is installed, and [fallback] when accessing it throws. A
 * desktop process without Compose Main uses the fallback so a CLI [MapState] can still commit.
 */
internal fun resolveHostDispatcher(
  main: () -> CoroutineDispatcher,
  fallback: () -> CoroutineDispatcher,
): CoroutineDispatcher = runCatching { main() }.getOrElse { fallback() }

/** The dispatcher [MapState] commits on when the caller does not supply one. */
internal expect fun defaultHostDispatcher(): CoroutineDispatcher
