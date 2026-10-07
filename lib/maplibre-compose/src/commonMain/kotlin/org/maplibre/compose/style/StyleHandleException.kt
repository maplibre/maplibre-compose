package org.maplibre.compose.style

/**
 * A style operation refused because no style is ready, its handle has expired, or the engine
 * rejected a command the caller waited for.
 *
 * Calls that correct code never makes throw [IllegalArgumentException] or [IllegalStateException]
 * instead: for example, writing a resource that the style content declares. Coroutine cancellation
 * retains its cancellation exception. Engine rejections of commands nothing waits for are logged
 * and retain the previous value.
 */
public class StyleHandleException internal constructor(message: String, cause: Throwable? = null) :
  RuntimeException(message, cause)

internal inline fun checkStyleHandle(value: Boolean, message: () -> String) {
  if (!value) throw StyleHandleException(message())
}

internal interface StyleHandleOperationGuard {
  fun <T> run(action: () -> T): T

  fun isSourceWritable(id: String): Boolean

  fun isLayerWritable(id: String): Boolean

  fun removeSource(id: String, identity: Any)

  fun requireSourceWritable(id: String)

  fun requireLayerWritable(id: String)
}
