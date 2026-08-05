package org.maplibre.compose.resource

import co.touchlab.kermit.Logger
import java.nio.file.Files
import org.maplibre.compose.desktop.DesktopRuntimeOptions
import org.maplibre.compose.offline.AmbientCacheSizeRequest
import org.maplibre.nativeffi.runtime.RuntimeEvent
import org.maplibre.nativeffi.runtime.RuntimeHandle
import org.maplibre.nativeffi.runtime.RuntimeOptions

/**
 * A MapLibre runtime together with the two things that have to outlive its creation and be retired
 * before its close: the ambient cache budget, and the resource provider.
 *
 * This exists because there are two runtimes on desktop — one per map, and one for the offline
 * manager — and they were creating and tearing down that trio independently. The steps are the same
 * and the order matters, so the duplication was not free: the offline runtime installed a provider
 * it never quiesced, which was invisible while the provider read inline on MapLibre's callback
 * thread (a runtime close waits on those) and became a real gap the moment reads moved to a worker.
 * Owning the sequence in one place is what stops the two drifting apart again.
 *
 * Owner-thread state throughout. A runtime belongs to the thread that created it, so this is
 * created, pumped, and closed on that one thread.
 */
internal class DesktopRuntimeOwner
private constructor(
  val runtime: RuntimeHandle,
  private val provider: DesktopResourceProvider,
  private val logger: Logger?,
) : AutoCloseable {

  /** The budget being applied, until its completion event arrives. */
  private var cacheSizeRequest: AmbientCacheSizeRequest? = null

  /**
   * Reports whether [event] was this owner's own bookkeeping rather than something the caller
   * should handle.
   *
   * The ambient cache budget completes as a runtime event like any other operation, and retiring it
   * is nobody else's business, so both pump loops give their events to this first.
   */
  fun consumeEvent(event: RuntimeEvent): Boolean {
    if (cacheSizeRequest?.consume(event) != true) return false
    cacheSizeRequest = null
    return true
  }

  /**
   * Retires everything the runtime close would otherwise trip over, then closes it.
   *
   * The order is the whole point. The cache-size operation goes first, because
   * `RuntimeHandle.close` blocks on operations still in flight and a budget nothing will observe
   * again should simply be cancelled. The provider goes next, because it answers on a thread of its
   * own: a read still running is a completion that would otherwise land on a runtime that is
   * already gone. Only then is there nothing left for the runtime to wait on.
   */
  override fun close() {
    cacheSizeRequest?.close()
    cacheSizeRequest = null
    runCatching { provider.close() }
      .onFailure { logger?.w(it) { "Failed to quiesce the desktop resource provider" } }
    runCatching { runtime.close() }
      .onFailure { logger?.e(it) { "Failed to close the MapLibre runtime" } }
  }

  companion object {
    /**
     * Creates a runtime and everything that hangs off it, or throws having closed whatever it got
     * as far as.
     *
     * [what] names the runtime in log lines, because two of these exist and "created the MapLibre
     * runtime" twice in a log says nothing about which.
     */
    fun open(options: DesktopRuntimeOptions, logger: Logger?, what: String): DesktopRuntimeOwner {
      // Created eagerly: MapLibre opens the database when the runtime is created and fails if the
      // directory is missing, which on a fresh machine it always is.
      runCatching { options.cachePath.parent?.let(Files::createDirectories) }
        .onFailure { logger?.w(it) { "Could not create the MapLibre cache directory" } }

      val runtime =
        RuntimeHandle.create(RuntimeOptions().also { it.cachePath = options.cachePath.toString() })
      val provider = DesktopResourceProvider(logger)
      val owner = DesktopRuntimeOwner(runtime, provider, logger)
      return try {
        // The budget is started before the provider so it is in force before any response can be
        // cached against it. Its answer arrives as an event, which consumeEvent retires.
        owner.cacheSizeRequest =
          AmbientCacheSizeRequest.start(runtime, options.maximumCacheSizeBytes, logger)
        // Installed with the runtime rather than with the map, so nothing can request a resource
        // before the provider that serves it exists. The offline runtime needs it too: a pack whose
        // style lives in the application's resources downloads through this same stack.
        runtime.setResourceProvider(provider)
        logger?.i { "Created the $what on ${Thread.currentThread().name}" }
        owner
      } catch (error: Throwable) {
        // Whatever was reached has to come back down in the same order a successful close uses, or
        // the runtime's scheduler and database connection stay open for the life of the process.
        owner.close()
        throw error
      }
    }
  }
}
