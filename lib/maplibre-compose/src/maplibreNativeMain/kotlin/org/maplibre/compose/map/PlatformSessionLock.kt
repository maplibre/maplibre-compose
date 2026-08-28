package org.maplibre.compose.map

import org.maplibre.compose.mlnffi.MlnFfiLock
import org.maplibre.compose.mlnffi.withLock

internal actual fun newSessionLock(): SessionLock {
  val lock = MlnFfiLock()
  return object : SessionLock {
    override fun <T> withLock(block: () -> T): T = lock.withLock(block)
  }
}
