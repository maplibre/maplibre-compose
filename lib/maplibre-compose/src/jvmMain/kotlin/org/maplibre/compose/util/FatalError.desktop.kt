package org.maplibre.compose.util

internal actual fun rethrowIfFatal(error: Throwable) {
  if (error is VirtualMachineError) throw error
}
