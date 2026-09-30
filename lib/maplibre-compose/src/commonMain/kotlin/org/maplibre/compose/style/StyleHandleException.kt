package org.maplibre.compose.style

/**
 * A style operation refused because no style is ready, its handle has expired, composition owns the
 * resource, or the engine rejected a command the caller waited for.
 *
 * Invalid arguments throw [IllegalArgumentException], and coroutine cancellation retains its
 * cancellation exception. Engine rejections of commands nothing waits for are logged and retain the
 * previous value.
 */
public class StyleHandleException(message: String, cause: Throwable? = null) :
  IllegalStateException(message, cause)

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
