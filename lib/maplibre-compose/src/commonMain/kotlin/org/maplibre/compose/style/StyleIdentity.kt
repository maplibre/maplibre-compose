package org.maplibre.compose.style

import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock

/** Opaque identity for one loaded base-style generation. */
internal class StyleIdentity private constructor() {
  val sources = ResourceIdentities()
  val layers = ResourceIdentities()
  val images = ResourceIdentities()

  companion object {
    fun create(): StyleIdentity = StyleIdentity()
  }
}

/** Resource identity follows the installed object, including replacement under the same ID. */
internal class ResourceIdentities {
  private val lock = reentrantLock()
  private val identities = mutableMapOf<String, Any>()

  fun get(id: String): Any = lock.withLock { identities.getOrPut(id) { Any() } }

  fun isCurrent(id: String, identity: Any): Boolean = lock.withLock { identities[id] === identity }

  fun retain(ids: Set<String>) {
    lock.withLock { identities.keys.retainAll(ids) }
  }

  fun remove(id: String) {
    lock.withLock { identities.remove(id) }
  }
}
