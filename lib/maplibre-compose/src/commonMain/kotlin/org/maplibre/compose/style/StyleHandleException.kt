package org.maplibre.compose.style

/**
 * A style handle command that the library refused, such as changing a resource owned by style
 * content, or a command the caller waited for that the engine rejected. Engine rejections of
 * commands nothing waits for are logged and retain the previous value.
 */
public class StyleHandleException(message: String, cause: Throwable? = null) :
  RuntimeException(message, cause)

internal interface StyleHandleOperationGuard {
  fun <T> run(action: () -> T): T

  fun isSourceWritable(id: String): Boolean

  fun isLayerWritable(id: String): Boolean

  fun removeSource(id: String, identity: Any)

  fun requireSourceWritable(id: String)

  fun requireLayerWritable(id: String)
}
