package org.maplibre.compose.resource

import co.touchlab.kermit.Logger
import java.nio.file.Files
import org.maplibre.compose.mlnffi.MlnFfiRuntimeOptions
import org.maplibre.compose.offline.AmbientCacheSizeRequest
import org.maplibre.nativeffi.runtime.RuntimeEvent
import org.maplibre.nativeffi.runtime.RuntimeHandle
import org.maplibre.nativeffi.runtime.RuntimeOptions

/**
 * A MapLibre runtime together with the two things that have to outlive its creation and begin
 * teardown before its close: the ambient cache budget, and the resource provider. Provider-owned
 * request handles remain valid until the provider releases them, even if a slow read outlasts the
 * runtime.
 *
 * Owner-thread state throughout: a runtime belongs to the thread that created it, so this is
 * created, pumped, and closed on that one thread.
 */
internal class MlnFfiRuntimeOwner
private constructor(
  val runtime: RuntimeHandle,
  private val provider: MlnFfiResourceProvider,
  private val logger: Logger?,
) : AutoCloseable {

  /** The budget being applied, until its completion event arrives. */
  private var cacheSizeRequest: AmbientCacheSizeRequest? = null

  /**
   * Reports whether [event] was this owner's own bookkeeping rather than something the caller
   * should handle. Every pump loop must offer its events here first.
   */
  fun consumeEvent(event: RuntimeEvent): Boolean {
    if (cacheSizeRequest?.consume(event) != true) return false
    cacheSizeRequest = null
    return true
  }

  /**
   * Starts teardown of everything attached to the runtime, then closes it.
   *
   * Order matters: `RuntimeHandle.close` blocks on in-flight operations, so the cache-size request
   * is cancelled first and the provider stops accepting reads next. Its drain is bounded; request
   * handles held by slower reads safely outlive the runtime and observe cancellation afterward.
   */
  override fun close() {
    cacheSizeRequest?.close()
    cacheSizeRequest = null
    runCatching { provider.close() }
      .onFailure { logger?.w(it) { "Failed to drain the resource provider" } }
    runCatching { runtime.close() }
      .onFailure { logger?.e(it) { "Failed to close the MapLibre runtime" } }
  }

  companion object {
    /**
     * Creates a runtime and everything that hangs off it, or throws having closed whatever it got
     * as far as. [what] names the runtime in log lines.
     */
    fun open(options: MlnFfiRuntimeOptions, logger: Logger?, what: String): MlnFfiRuntimeOwner {
      // MapLibre opens the database as the runtime is created, and fails if the directory is
      // missing.
      runCatching { options.cachePath.parent?.let(Files::createDirectories) }
        .onFailure { logger?.w(it) { "Could not create the MapLibre cache directory" } }

      val runtime =
        RuntimeHandle.create(RuntimeOptions().also { it.cachePath = options.cachePath.toString() })
      val provider = MlnFfiResourceProvider(logger)
      val owner = MlnFfiRuntimeOwner(runtime, provider, logger)
      return try {
        // Started before the provider so the budget is in force before any response can be cached.
        owner.cacheSizeRequest =
          AmbientCacheSizeRequest.start(runtime, options.maximumCacheSizeBytes, logger)
        // Installed with the runtime rather than with the map, so nothing can request a resource
        // before the provider that serves it exists.
        runtime.setResourceProvider(provider)
        logger?.i { "Created the $what on ${Thread.currentThread().name}" }
        owner
      } catch (error: Throwable) {
        // Unwinds in the same order a successful close uses, or the runtime's scheduler and
        // database
        // connection stay open for the life of the process.
        owner.close()
        throw error
      }
    }
  }
}
