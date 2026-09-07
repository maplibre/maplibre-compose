@file:OptIn(ExperimentalAtomicApi::class)

package org.maplibre.compose.style

import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** Opaque identity for one loaded base-style generation. */
internal class StyleIdentity private constructor() {
  val sources = ResourceIdentities()
  val layers = ResourceIdentities()

  companion object {
    fun create(): StyleIdentity = StyleIdentity()
  }
}

/** Resource identity follows the installed object, including replacement under the same ID. */
internal class ResourceIdentities {
  private val identities = AtomicReference<Map<String, Any>>(emptyMap())

  fun get(id: String): Any {
    while (true) {
      val current = identities.load()
      current[id]?.let {
        return it
      }
      val identity = Any()
      if (identities.compareAndSet(current, current + (id to identity))) return identity
    }
  }

  fun isCurrent(id: String, identity: Any): Boolean = identities.load()[id] === identity

  fun retain(ids: Set<String>) {
    while (true) {
      val current = identities.load()
      val retained = current.filterKeys { it in ids }
      if (retained.size == current.size || identities.compareAndSet(current, retained)) return
    }
  }

  fun remove(id: String) {
    while (true) {
      val current = identities.load()
      val remaining = current - id
      if (remaining.size == current.size || identities.compareAndSet(current, remaining)) return
    }
  }
}
