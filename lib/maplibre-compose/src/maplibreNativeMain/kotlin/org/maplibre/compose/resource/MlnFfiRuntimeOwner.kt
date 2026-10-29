@file:OptIn(org.maplibre.compose.util.ExperimentalMaplibreComposeApi::class)

package org.maplibre.compose.resource

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import org.maplibre.compose.logging.MapLog
import org.maplibre.compose.mlnffi.MlnFfiLogBridge
import org.maplibre.compose.mlnffi.normalizeMlnFfiPath
import org.maplibre.compose.mlnffi.reportFailure
import org.maplibre.nativeffi.ResourceProvider
import org.maplibre.nativeffi.RuntimeEventHandler
import org.maplibre.nativeffi.RuntimeHandle
import org.maplibre.nativeffi.RuntimeOptions

/**
 * A MapLibre runtime together with its resource provider. Provider-owned request handles remain
 * valid until the provider releases them, even if a slow read outlasts the runtime.
 */
internal class MlnFfiRuntimeOwner
private constructor(
  val runtime: RuntimeHandle,
  private val provider: MlnFfiResourceProvider,
  private val networkMonitor: AutoCloseable,
) {

  /**
   * Stops the provider accepting reads, then releases the runtime and waits for its native
   * teardown. Request handles held by slower reads safely outlive the runtime and observe
   * cancellation.
   */
  suspend fun close() =
    withContext(NonCancellable) {
      try {
        provider.close()
        runtime.release().await()
      } finally {
        networkMonitor.close()
      }
    }

  companion object {
    /**
     * Creates a runtime and everything that hangs off it, or throws having released whatever it got
     * as far as. [what] names the runtime in log lines.
     */
    suspend fun open(
      rawCacheFile: Path,
      getLogger: () -> MapLog?,
      what: String,
      resourceProviderFactory: MlnFfiResourceProviderFactory = ::MlnFfiResourceProvider,
      resourceConfig: MapResourceConfig = MapResourceConfig(),
      eventHandler: RuntimeEventHandler? = null,
    ): MlnFfiRuntimeOwner {
      val cacheFile = normalizeMlnFfiPath(rawCacheFile)
      // MapLibre opens the database as the runtime is created, and fails if the directory is
      // missing.
      runCatching { cacheFile.parent?.let { SystemFileSystem.createDirectories(it) } }
        .onFailure { getLogger()?.w(it) { "Could not create the MapLibre cache directory" } }

      MlnFfiLogBridge.ensureInstalled()
      val runtime =
        RuntimeHandle.create(
          RuntimeOptions(cachePath = cacheFile.toString()),
        )
      try {
        eventHandler?.let { runtime.setEventHandler(it).await() }
      } catch (error: Throwable) {
        runCatching { runtime.release().await() }.exceptionOrNull()?.let(error::addSuppressed)
        throw error
      }
      val networkMonitor =
        try {
          MapConnectivity.acquireMonitor()
        } catch (error: Throwable) {
          runCatching { runtime.release().await() }.exceptionOrNull()?.let(error::addSuppressed)
          throw error
        }
      val provider =
        try {
          resourceProviderFactory(getLogger, resourceConfig)
        } catch (error: Throwable) {
          runCatching { runtime.release().await() }.exceptionOrNull()?.let(error::addSuppressed)
          runCatching { networkMonitor.close() }.exceptionOrNull()?.let(error::addSuppressed)
          throw error
        }
      val owner = MlnFfiRuntimeOwner(runtime, provider, networkMonitor)
      return try {
        // Installed with the runtime rather than with the map. Runtime work runs in submission
        // order, so nothing can request a resource before the provider that serves it exists.
        runtime
          .setResourceProvider(ResourceProvider(callback = provider::handle))
          .reportFailure(getLogger(), "install the resource provider")
        runtime.installRequestInterceptor(resourceConfig)
        getLogger()?.i { "Created the $what" }
        owner
      } catch (error: Throwable) {
        runCatching { owner.close() }.exceptionOrNull()?.let(error::addSuppressed)
        throw error
      }
    }
  }
}
