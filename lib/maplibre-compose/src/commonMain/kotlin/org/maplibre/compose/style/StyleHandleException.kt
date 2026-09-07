package org.maplibre.compose.style

/**
 * A style handle command that was refused before it reached the engine's thread: by the library,
 * for a resource that style content owns or a handle whose style is no longer ready, or by
 * MapLibre, for a structural command such as adding a source or image. A property write that
 * MapLibre rejects is logged instead, and the previous value stays in place.
 */
public class StyleHandleException(message: String, cause: Throwable? = null) :
  RuntimeException(message, cause)

internal interface StyleHandleOperationGuard {
  fun <T> run(action: () -> T): T

  fun requireSourceWritable(id: String)

  fun requireLayerWritable(id: String)
}
