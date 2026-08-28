package org.maplibre.compose.map

/**
 * A lock for common state that several threads mutate; a single-threaded platform skips locking.
 */
internal expect fun newSessionLock(): SessionLock
