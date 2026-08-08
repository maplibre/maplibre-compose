package org.maplibre.compose.offline

import co.touchlab.kermit.Logger
import org.maplibre.compose.resource.MlnFfiCacheDatabaseWritePermit
import org.maplibre.nativeffi.error.MaplibreStatus
import org.maplibre.nativeffi.runtime.OfflineOperationHandle
import org.maplibre.nativeffi.runtime.RuntimeEvent
import org.maplibre.nativeffi.runtime.RuntimeEventPayload
import org.maplibre.nativeffi.runtime.RuntimeHandle

/**
 * The ambient cache budget being applied to a runtime that was just created.
 *
 * Applying it is an offline operation awaited through the event pump, and closing the handle before
 * its completion arrives cancels it, so this outlives the call that started it.
 */
internal class AmbientCacheSizeRequest
private constructor(
  private val handle: OfflineOperationHandle<Unit>,
  private val sizeBytes: Long,
  private val logger: Logger?,
  private val writePermit: MlnFfiCacheDatabaseWritePermit,
) {

  /**
   * Reports whether [event] completed this request, retiring it when it did. A failure is logged
   * rather than raised; the runtime is usable either way.
   */
  fun consume(event: RuntimeEvent): Boolean {
    val payload = event.payload as? RuntimeEventPayload.OfflineOperationCompleted ?: return false
    if (payload.operationId != handle.id) return false
    if (payload.resultStatus == MaplibreStatus.OK.nativeCode) {
      logger?.d { "Ambient cache size set to $sizeBytes bytes" }
    } else {
      val detail = event.message.ifBlank { "status ${payload.resultStatus}" }
      logger?.w { "Could not set the ambient cache size to $sizeBytes bytes: $detail" }
    }
    close()
    return true
  }

  /**
   * Drops the request. Only call this once the completion has arrived, or during teardown; earlier
   * it cancels a budget the application asked for.
   */
  fun close() {
    try {
      runCatching { handle.close() }
        .onFailure { logger?.w(it) { "Failed to close the ambient cache size operation" } }
    } finally {
      writePermit.close()
    }
  }

  companion object {
    /**
     * Starts applying [sizeBytes] to [runtime], or returns null when there is nothing to apply.
     * Must run on the runtime's owner thread before anything else uses it.
     */
    fun start(
      runtime: RuntimeHandle,
      writePermit: MlnFfiCacheDatabaseWritePermit,
      logger: Logger?,
    ): AmbientCacheSizeRequest? {
      val sizeBytes = writePermit.effectiveMaximumCacheSizeBytes
      if (sizeBytes == null) return null
      return try {
        AmbientCacheSizeRequest(
          handle = runtime.startSetMaximumAmbientCacheSize(sizeBytes),
          sizeBytes = sizeBytes,
          logger = logger,
          writePermit = writePermit,
        )
      } catch (error: Throwable) {
        // Reported rather than fatal: a runtime that kept MapLibre's default budget still works.
        logger?.w(error) { "Could not ask for an ambient cache size of $sizeBytes bytes" }
        writePermit.close()
        null
      }
    }
  }
}
