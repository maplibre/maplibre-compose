package org.maplibre.compose.map

import org.maplibre.compose.camera.CameraPosition

/**
 * The single serialized authority for a [MapState]'s logical transitions.
 *
 * Every public request and every platform callback becomes a reduce against [record]. The lock
 * exists only to serialize those turns. The caller publishes Compose snapshots and runs
 * [MapEffect]s after the token is released, so user callbacks and owner-thread hops never hold it.
 *
 * Stale work is unauthorized by identity: a style event names a generation, a session event names
 * an adapter, a composition publish names a binding. A superseded identity is a no-op.
 */
internal class MapKernel(initialCamera: CameraPosition) {
  private val lock = newSessionLock()

  internal val record = MapRecord(initialCamera)

  /**
   * Applies [transform] to the record under the serial token and returns the effects to run after
   * the caller has published the record. Do not call platform code or user callbacks from
   * [transform].
   */
  fun reduce(transform: MapRecord.() -> Unit): List<MapEffect> = lock.withLock {
    record.effects.clear()
    record.transform()
    record.takeEffects()
  }

  /** Like [reduce], but returns a value the caller needs after the turn. */
  fun <T> reduceValue(transform: MapRecord.() -> T): Pair<T, List<MapEffect>> = lock.withLock {
    record.effects.clear()
    val value = record.transform()
    value to record.takeEffects()
  }

  /** A consistent read of the record. The block must not mutate it. */
  fun <T> read(transform: MapRecord.() -> T): T = lock.withLock { record.transform() }
}
