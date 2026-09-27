package org.maplibre.compose.style

/**
 * A style handle command that the library refused, such as changing a resource owned by style
 * content. Engine rejections of queued commands are logged and retain the previous value.
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
