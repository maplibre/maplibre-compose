package org.maplibre.compose.style

/**
 * A style operation refused because its handle has expired, or because the engine rejected a
 * command the caller waited for.
 *
 * Calls that correct code never makes throw [IllegalArgumentException] or [IllegalStateException]
 * instead: for example, writing a resource that the style content declares, or using a closed map
 * state. A command that finds no ready style, or whose loaded style changes before it runs, does
 * nothing and logs a warning. Coroutine cancellation retains its cancellation exception. Engine
 * rejections of commands nothing waits for are logged and retain the previous value.
 */
public class StyleHandleException internal constructor(message: String, cause: Throwable? = null) :
  RuntimeException(message, cause)

internal inline fun checkStyleHandle(value: Boolean, message: () -> String) {
  if (!value) throw StyleHandleException(message())
}

internal interface StyleHandleOperationGuard {
  fun <T> run(action: () -> T): T

  /**
   * Checks again, after a suspension, that the loaded style is still ready. A close during the
   * suspension reads as a style change, not as use after close.
   */
  fun requireReady()

  fun isSourceWritable(id: String): Boolean

  fun isLayerWritable(id: String): Boolean

  fun removeSource(id: String, identity: Any)

  fun requireSourceWritable(id: String)

  fun requireLayerWritable(id: String)
}
