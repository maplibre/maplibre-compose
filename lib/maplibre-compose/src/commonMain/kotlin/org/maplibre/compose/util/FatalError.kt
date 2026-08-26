package org.maplibre.compose.util

/** Rethrows [error] when the process cannot be expected to keep running. */
internal expect fun rethrowIfFatal(error: Throwable)
