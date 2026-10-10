@file:OptIn(ExperimentalCoroutinesApi::class)

package org.maplibre.compose.mlnffi

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import org.maplibre.compose.logging.MapLog
import org.maplibre.nativeffi.error.MaplibreException
import org.maplibre.nativeffi.error.MaplibreStatus
import org.maplibre.nativeffi.generated.CommandDisposition
import org.maplibre.nativeffi.generated.MapProjectionHandle
import org.maplibre.nativeffi.runtime.CommandCompletion

/** Whether the command ended with a terminal failure. Supersession and cancellation are not. */
internal val CommandCompletion.failed: Boolean
  get() = disposition == CommandDisposition.FAILED

/** The failure a command ended with, as the binding reports a rejected submission. */
internal fun CommandCompletion.failureMessage(): String = diagnostic.ifBlank {
  "MapLibre reported $status"
}

/** Whether the command failed because what it names does not exist. */
internal val CommandCompletion.notFound: Boolean
  get() = failed && status == MaplibreStatus.NOT_FOUND

/**
 * Logs the terminal failure of work nobody waits on. Without this, an asynchronous failure of a
 * fire-and-forget command would go unreported.
 */
internal fun <T> Deferred<T>.reportFailure(logger: MapLog?, what: String): Deferred<T> {
  if (logger == null) return this
  invokeOnCompletion { error ->
    when {
      error is CancellationException -> Unit
      error != null -> logger.w(error) { "Could not $what" }
      else -> {
        val completion = getCompleted() as? CommandCompletion ?: return@invokeOnCompletion
        if (completion.failed) logger.w { "Could not $what: ${completion.failureMessage()}" }
      }
    }
  }
  return this
}

/**
 * Waits on the calling thread for native work to finish. Only for code that already runs on a
 * background thread and has a synchronous contract to keep, such as a style visit.
 */
internal fun <T> Deferred<T>.awaitBlocking(): T =
  if (isCompleted) getCompleted() else runBlocking { await() }

/** Waits for a command and throws its terminal failure. */
internal fun Deferred<CommandCompletion>.awaitCommittedBlocking(): CommandCompletion {
  val completion = awaitBlocking()
  if (completion.failed) throw CommandFailedException(completion)
  return completion
}

/** A command that native accepted and then failed. */
internal class CommandFailedException(val completion: CommandCompletion) :
  RuntimeException(completion.failureMessage())

/** Whether [error] reports that its handle closed, or closed while the call ran. */
internal fun MaplibreException.isClosedHandle(): Boolean =
  status == MaplibreStatus.INVALID_STATE && nativeStatusCode == null

/** Runs [block] with this projection and closes it however [block] ends. */
internal inline fun <T> MapProjectionHandle.use(block: (MapProjectionHandle) -> T): T =
  try {
    block(this)
  } finally {
    close()
  }
