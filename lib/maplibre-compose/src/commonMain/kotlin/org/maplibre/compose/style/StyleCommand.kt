package org.maplibre.compose.style

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred

/**
 * The engine's outcome for one structural write: adding, removing, or moving a source, layer, or
 * image. It completes on main once the engine has run the write, and fails with
 * [StyleMutationException] when the engine rejected it. Browser writes complete before the call
 * returns.
 */
internal typealias StyleCommand = Deferred<Unit>

internal fun acceptedStyleCommand(): StyleCommand = CompletableDeferred(Unit)

internal fun rejectedStyleCommand(error: StyleMutationException): StyleCommand =
  CompletableDeferred<Unit>().apply { completeExceptionally(error) }

/** Runs [block] inline and reports its [StyleMutationException] through the returned command. */
internal inline fun inlineStyleCommand(block: () -> Unit): StyleCommand =
  try {
    block()
    acceptedStyleCommand()
  } catch (error: StyleMutationException) {
    rejectedStyleCommand(error)
  }

/**
 * Runs [onRejected] on main if the engine rejects this write. A write that succeeds, or whose
 * caller cancelled, runs nothing.
 */
internal fun StyleCommand.onRejected(onRejected: (StyleMutationException) -> Unit) {
  invokeOnCompletion { error -> if (error is StyleMutationException) onRejected(error) }
}
