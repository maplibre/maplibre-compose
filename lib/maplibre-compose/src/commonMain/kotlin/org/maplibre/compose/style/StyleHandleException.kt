package org.maplibre.compose.style

/**
 * The engine rejected a style command that the caller waited for, such as a source definition in
 * [org.maplibre.compose.map.StyleSources.add], or failed a query.
 *
 * Calls that correct code never makes throw [IllegalArgumentException] or [IllegalStateException]
 * instead: for example, writing a resource that the style content declares, or using a closed map
 * state. A command that finds no ready style, or whose loaded style changes before it runs, does
 * nothing and logs a warning. A handle whose loaded style has reloaded reads null and ignores
 * writes. Coroutine cancellation retains its cancellation exception. Engine rejections of commands
 * nothing waits for are logged and retain the previous value.
 */
public class StyleHandleException internal constructor(message: String, cause: Throwable? = null) :
  RuntimeException(message, cause)

/** What the handles of one loaded style check before each operation. */
internal interface StyleHandleOperationGuard {
  /**
   * @throws IllegalStateException once the map state or snapshotter has closed, or off the thread
   *   that it requires.
   */
  fun requireOpen()

  /**
   * Returns true while the handles' loaded style is the ready one and its owner is open, so a close
   * during an operation reads like an expired handle.
   */
  fun isReady(): Boolean

  /** Posts a write through the owner's single write path; see `MapStyleState.post`. */
  fun post(target: String, isResourceCurrent: () -> Boolean, action: () -> Unit)

  /** Runs a handle read through the owner's single read path; see `MapStyleState.read`. */
  suspend fun <T> read(isResourceCurrent: () -> Boolean, action: suspend () -> T?): T?

  /** Runs a read through the owner's single engine path; see `MapStyleState.visit`. */
  suspend fun <T> visit(action: () -> T?): T?

  fun isSourceWritable(id: String): Boolean

  fun isLayerWritable(id: String): Boolean

  /** Enqueues removal; [onRemoved] runs once the removal has happened. */
  fun removeSource(id: String, identity: Any, onRemoved: () -> Unit)

  fun requireSourceWritable(id: String)

  fun requireLayerWritable(id: String)
}
