package org.maplibre.compose.style

/** A live style handle operation that MapLibre refused. */
public class StyleHandleException(message: String, cause: Throwable? = null) :
  RuntimeException(message, cause)

internal interface StyleHandleOperationGuard {
  fun <T> run(action: () -> T): T

  fun checkpoint(): Long

  fun requireUnchanged(checkpoint: Long)
}
