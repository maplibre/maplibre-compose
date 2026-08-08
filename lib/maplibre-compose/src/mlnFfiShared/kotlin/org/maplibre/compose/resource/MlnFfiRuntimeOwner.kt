package org.maplibre.compose.resource

import co.touchlab.kermit.Logger
import java.nio.file.Files
import java.nio.file.Path
import org.maplibre.nativeffi.runtime.RuntimeHandle
import org.maplibre.nativeffi.runtime.RuntimeOptions

/**
 * A MapLibre runtime together with its resource provider. Provider-owned request handles remain
 * valid until the provider releases them, even if a slow read outlasts the runtime.
 *
 * Owner-thread state throughout: a runtime belongs to the thread that created it, so this is
 * created, pumped, and closed on that one thread.
 */
internal class MlnFfiRuntimeOwner
private constructor(
  val runtime: RuntimeHandle,
  private val provider: MlnFfiResourceProvider,
  private val getLogger: () -> Logger?,
) : AutoCloseable {
  private val logger: Logger?
    get() = getLogger()

  /**
   * Starts teardown of everything attached to the runtime, then closes it.
   *
   * The provider stops accepting reads before `RuntimeHandle.close`, whose drain is bounded;
   * request handles held by slower reads safely outlive the runtime and observe cancellation.
   */
  override fun close() {
    runCatching { provider.close() }
      .onFailure { logger?.w(it) { "Failed to close the resource provider" } }
    runCatching { runtime.close() }
      .onFailure { logger?.e(it) { "Failed to close the MapLibre runtime" } }
  }

  companion object {
    /**
     * Creates a runtime and everything that hangs off it, or throws having closed whatever it got
     * as far as. [what] names the runtime in log lines.
     */
    fun open(rawCachePath: Path, getLogger: () -> Logger?, what: String): MlnFfiRuntimeOwner {
      val cachePath = rawCachePath.toAbsolutePath().normalize()
      // MapLibre opens the database as the runtime is created, and fails if the directory is
      // missing.
      runCatching { cachePath.parent?.let(Files::createDirectories) }
        .onFailure { getLogger()?.w(it) { "Could not create the MapLibre cache directory" } }

      val runtime =
        try {
          RuntimeHandle.create(RuntimeOptions().also { it.cachePath = cachePath.toString() })
        } catch (error: Throwable) {
          throw error
        }
      val provider =
        try {
          MlnFfiResourceProvider(getLogger = getLogger)
        } catch (error: Throwable) {
          runCatching { runtime.close() }
          throw error
        }
      val owner = MlnFfiRuntimeOwner(runtime, provider, getLogger)
      return try {
        // Installed with the runtime rather than with the map, so nothing can request a resource
        // before the provider that serves it exists.
        runtime.setResourceProvider(provider)
        getLogger()?.i { "Created the $what on ${Thread.currentThread().name}" }
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
