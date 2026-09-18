package org.maplibre.compose.mlnffi

/** Keeps a test resource reusable until its last owner releases it and the idle delay expires. */
internal class SharedTestResource<T : Any>(
  private val create: () -> T,
  private val dispose: (T) -> Unit,
  private val scheduleDisposal: (() -> Unit) -> Unit,
) {
  private val lock = Any()
  private var resource: T? = null
  private var owners = 0
  private var generation = 0L

  fun acquire(): T =
    synchronized(lock) {
      val acquired = resource ?: create().also { resource = it }
      owners += 1
      generation += 1
      acquired
    }

  fun release(released: T) {
    val scheduledGeneration =
      synchronized(lock) {
        check(resource === released && owners > 0) { "Resource has no matching owner" }
        owners -= 1
        if (owners > 0) return
        ++generation
      }
    scheduleDisposal {
      val shouldDispose =
        synchronized(lock) {
          if (resource === released && owners == 0 && generation == scheduledGeneration) {
            resource = null
            true
          } else {
            false
          }
        }
      if (shouldDispose) dispose(released)
    }
  }
}
