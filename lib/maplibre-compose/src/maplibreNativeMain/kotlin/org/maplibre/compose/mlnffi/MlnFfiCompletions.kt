@file:OptIn(ExperimentalCoroutinesApi::class)

package org.maplibre.compose.mlnffi

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.maplibre.compose.logging.MapLog
import org.maplibre.nativeffi.MapProjectionHandle

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
    }
  }
  return this
}

/** Runs [block] with this projection and closes it however [block] ends. */
internal inline fun <T> MapProjectionHandle.use(block: (MapProjectionHandle) -> T): T =
  try {
    block(this)
  } finally {
    close()
  }
