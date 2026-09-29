package org.maplibre.compose.map

import org.maplibre.compose.mlnffi.MlnFfiGate
import org.maplibre.nativeffi.map.MapHandle

/**
 * Blocks the calling thread until [action] has run on the owner thread, and returns its result.
 * Inline on the owner. Returns null when the map does not exist yet or the loop stops first. Ends
 * its batch, so work queued after it sees the events [action] raised. Tests only: production code
 * suspends with [MlnFfiMapRuntimeLoop.await].
 */
internal fun <T> MlnFfiMapRuntimeLoop.readBlocking(action: (MapHandle) -> T): T? {
  if (isOwnerThread()) return map?.let(action)
  if (map == null) return null
  var result: Result<T>? = null
  val done = MlnFfiGate()
  submit(ordered = true, onDropped = { done.open() }) { map ->
    result = runCatching { action(map) }
    done.open()
  }
  done.awaitUntilOpen()
  return result?.getOrThrow()
}
