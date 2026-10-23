package org.maplibre.compose.style

/** Opaque identity for one loaded base-style generation. */
internal class StyleIdentity private constructor() {
  val sources = ResourceIdentities()
  val layers = ResourceIdentities()
  val images = ResourceIdentities()

  companion object {
    fun create(): StyleIdentity = StyleIdentity()
  }
}

/**
 * Resource identity follows the installed object, including replacement under the same ID. Main
 * only.
 */
internal class ResourceIdentities {
  private val identities = mutableMapOf<String, Any>()

  fun get(id: String): Any = identities.getOrPut(id) { Any() }

  /** The identity [id] has now, without creating one. */
  fun peek(id: String): Any? = identities[id]

  fun isCurrent(id: String, identity: Any): Boolean = identities[id] === identity

  fun retain(ids: Set<String>) {
    identities.keys.retainAll(ids)
  }

  fun remove(id: String) {
    identities.remove(id)
  }

  /** Expires [id]'s identity and returns it, so a write the engine rejects can [restore] it. */
  fun take(id: String): Any? = identities.remove(id)

  /** Gives [id] back an identity that [take] returned. */
  fun restore(id: String, identity: Any) {
    identities[id] = identity
  }
}
