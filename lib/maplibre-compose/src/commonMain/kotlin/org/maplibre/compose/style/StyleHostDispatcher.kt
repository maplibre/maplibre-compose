package org.maplibre.compose.style

import kotlinx.coroutines.CoroutineDispatcher

/** A dispatcher the style composition host owns; [close] releases its thread. */
internal interface StyleHostDispatcher : AutoCloseable {
  val dispatcher: CoroutineDispatcher

  /** May be called from the dispatcher's own thread, so it must not block on its termination. */
  override fun close()
}

/**
 * A dispatcher for one style composition host: single-threaded, because style mutations block on
 * the map's owner-thread hop and must serialize.
 */
internal expect fun styleHostDispatcher(): StyleHostDispatcher
