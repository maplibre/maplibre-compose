package org.maplibre.compose.resource

import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import org.maplibre.compose.logging.MapLog
import org.maplibre.compose.mlnffi.MlnFfiLogBridge
import org.maplibre.compose.mlnffi.currentMlnFfiThreadName
import org.maplibre.compose.mlnffi.normalizeMlnFfiPath
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
  private val getLogger: () -> MapLog?,
) : AutoCloseable {
  private val logger: MapLog?
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
    fun open(
      rawCacheFile: Path,
      getLogger: () -> MapLog?,
      what: String,
      resourceProviderFactory: MlnFfiResourceProviderFactory = ::MlnFfiResourceProvider,
      resourceConfig: MapResourceConfig = MapResourceConfig(),
    ): MlnFfiRuntimeOwner {
      val cacheFile = normalizeMlnFfiPath(rawCacheFile)
      // MapLibre opens the database as the runtime is created, and fails if the directory is
      // missing.
      runCatching { cacheFile.parent?.let { SystemFileSystem.createDirectories(it) } }
        .onFailure { getLogger()?.w(it) { "Could not create the MapLibre cache directory" } }

      MlnFfiLogBridge.ensureInstalled()
      val runtime =
        try {
          RuntimeHandle.create(RuntimeOptions().also { it.cachePath = cacheFile.toString() })
        } catch (error: Throwable) {
          throw error
        }
      val provider =
        try {
          resourceProviderFactory(getLogger).also { it.userProvider = resourceConfig.provider }
        } catch (error: Throwable) {
          runCatching { runtime.close() }
          throw error
        }
      val owner = MlnFfiRuntimeOwner(runtime, provider, getLogger)
      return try {
        // Installed with the runtime rather than with the map, so nothing can request a resource
        // before the provider that serves it exists.
        runtime.setResourceProvider(provider)
        runtime.installRequestInterceptor(resourceConfig)
        getLogger()?.i { "Created the $what on ${currentMlnFfiThreadName()}" }
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
