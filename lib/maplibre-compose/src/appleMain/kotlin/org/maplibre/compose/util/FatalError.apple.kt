package org.maplibre.compose.util

/**
 * Kotlin/Native has no `VirtualMachineError` family; an exhausted heap is the one failure the
 * process cannot be expected to ride out.
 */
internal actual fun rethrowIfFatal(error: Throwable) {
  if (error is OutOfMemoryError) throw error
}
