package org.maplibre.compose.map

import org.maplibre.compose.mlnffi.MlnFfiGate
import org.maplibre.compose.mlnffi.MlnFfiLock
import org.maplibre.compose.mlnffi.currentMlnFfiThreadToken
import org.maplibre.compose.mlnffi.withLock

internal actual fun newSessionLock(): SessionLock {
  val lock = MlnFfiLock()
  return object : SessionLock {
    override fun <T> withLock(block: () -> T): T = lock.withLock(block)
  }
}

internal actual fun currentThreadToken(): Any = currentMlnFfiThreadToken()

internal actual fun newIdleGate(): IdleGate {
  val gate = MlnFfiGate()
  return object : IdleGate {
    override fun open() = gate.open()

    override fun await() = gate.awaitUntilOpen()
  }
}
