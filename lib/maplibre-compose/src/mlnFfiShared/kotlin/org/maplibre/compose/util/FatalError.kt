package org.maplibre.compose.util

/** Rethrows [error] when the process cannot be expected to keep running. */
internal fun rethrowIfFatal(error: Throwable) {
  if (error is VirtualMachineError) throw error
}
