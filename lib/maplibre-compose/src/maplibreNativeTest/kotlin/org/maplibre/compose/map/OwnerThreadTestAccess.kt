package org.maplibre.compose.map

import org.maplibre.compose.mlnffi.MlnFfiGate
import org.maplibre.nativeffi.map.MapHandle
import org.maplibre.nativeffi.style.StyleImageInfo

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

/** Blocks until [action] has run on this session's owner thread; see [readBlocking]. */
internal fun <T> MlnFfiMapSession.readMap(action: (MapHandle) -> T): T? = loop.readBlocking(action)

internal fun MlnFfiMapSession.currentStyleLayerIds(): List<String> = readMap {
  it.styleLayerIds()
}
  .orEmpty()

internal fun MlnFfiMapSession.styleImageInfo(imageId: String): StyleImageInfo? = readMap {
  it.styleImageInfo(imageId)
}
