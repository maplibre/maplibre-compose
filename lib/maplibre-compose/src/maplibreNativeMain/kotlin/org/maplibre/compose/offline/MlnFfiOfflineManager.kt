package org.maplibre.compose.offline

import androidx.compose.runtime.mutableStateOf
import co.touchlab.kermit.Logger
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.maplibre.compose.mlnffi.MlnFfiGate
import org.maplibre.compose.mlnffi.MlnFfiRuntimeOptions
import org.maplibre.nativeffi.error.MaplibreException
import org.maplibre.nativeffi.error.MaplibreStatus
import org.maplibre.nativeffi.offline.OfflineRegionDownloadState
import org.maplibre.nativeffi.offline.OfflineRegionInfo
import org.maplibre.nativeffi.runtime.AmbientCacheOperation
import org.maplibre.nativeffi.runtime.OfflineOperationHandle
import org.maplibre.nativeffi.runtime.RuntimeEvent
import org.maplibre.nativeffi.runtime.RuntimeEventPayload
import org.maplibre.nativeffi.runtime.RuntimeEventType
import org.maplibre.nativeffi.runtime.RuntimeHandle

/** The MapLibre Native FFI offline manager that belongs to one map runtime. */
internal class MlnFfiOfflineManager(private val options: MlnFfiRuntimeOptions) :
  OfflineManager, OfflinePackOwner {

  private val logger = Logger.withTag("maplibre-compose")

  /**
   * Compose state, written inline on the owner thread — never hopped to a dispatcher, which would
   * both assume an AWT host and lose the production order of status updates.
   */
  private val packsState = mutableStateOf(emptySet<OfflinePack>())

  /** Owner-thread state: the packs this manager has seen, keyed by native region id. */
  private val packsById = mutableMapOf<Long, OfflinePack>()

  private val runtime = MlnFfiOfflineRuntime(options.cacheFile, logger, ::handleEvent)

  /** One manager applies one cache-budget change at a time. */
  private val cacheBudgetMutex = Mutex()

  override val packs: Set<OfflinePack>
    get() = packsState.value

  init {
    runtime.start()
    awaitConfiguredRuntime()
    submit(
      description = "list the offline packs",
      start = { it.startOfflineRegions() },
      finish = { nativeRuntime, handle ->
        nativeRuntime.takeOfflineRegionsResult(handle).forEach { info ->
          // One unrepresentable region must not cost the user the rest of their packs.
          runCatching { registerRegion(info) }
            .onFailure { logger.w(it) { "Ignoring offline region ${info.id}" } }
        }
      },
    )
  }

  /** Publishes the manager after runtime startup and initial cache-budget configuration succeed. */
  @OptIn(ExperimentalAtomicApi::class)
  private fun awaitConfiguredRuntime() {
    val settled = MlnFfiGate()
    val completed = AtomicBoolean(false)
    var outcome: Result<Unit>? = null
    fun complete(result: Result<Unit>) {
      if (completed.compareAndSet(false, true)) {
        outcome = result
        settled.open()
      }
    }

    val initialSize = options.maximumCacheSizeBytes
    val accepted =
      if (initialSize == null) {
        runtime.post(
          task = { complete(Result.success(Unit)) },
          reject = { complete(Result.failure(it)) },
        )
      } else {
        submit(
          description = "set the initial maximum ambient cache size to $initialSize bytes",
          start = { it.startSetMaximumAmbientCacheSize(initialSize) },
          finish = { _, _ -> },
          onResult = { result -> complete(result) },
        )
      }
    if (!accepted) {
      complete(
        Result.failure(OfflineManagerException("The offline runtime rejected configuration"))
      )
    }

    settled.awaitUntilOpen()
    val settledOutcome =
      outcome
        ?: failStartup(
          "Could not configure MapLibre's offline runtime",
          OfflineManagerException("The offline runtime never reported its configuration"),
        )
    val failure = settledOutcome.exceptionOrNull()
    if (failure != null) failStartup("Could not configure MapLibre's offline runtime", failure)
    if (initialSize != null) logger.d { "Ambient cache size set to $initialSize bytes" }
  }

  private fun failStartup(message: String, cause: Throwable): Nothing {
    runtime.shutdown()
    runCatching { runtime.awaitStopped(30_000) }
    throw IllegalStateException(message, cause)
  }

  override suspend fun create(definition: OfflinePackDefinition, metadata: ByteArray): OfflinePack {
    return create(definition, metadata, pixelRatio = 1f)
  }

  internal suspend fun create(
    definition: OfflinePackDefinition,
    metadata: ByteArray,
    pixelRatio: Float,
  ): OfflinePack {
    val ffiDefinition = definition.toFfiRegionDefinition(pixelRatio)
    // Copied because the caller still owns the array it passed and native reads it later.
    val ffiMetadata = metadata.copyOf()
    return runOperation(
      description = "create an offline pack",
      start = { it.startCreateOfflineRegion(ffiDefinition, ffiMetadata) },
      finish = { nativeRuntime, handle ->
        registerRegion(nativeRuntime.takeCreateOfflineRegionResult(handle))
          ?: throw OfflineManagerException(
            "MapLibre created an offline region that this build cannot represent"
          )
      },
    )
  }

  override fun resume(pack: OfflinePack) {
    requireOwned(pack)
    setDownloadState(pack, OfflineRegionDownloadState.ACTIVE)
  }

  override fun pause(pack: OfflinePack) {
    requireOwned(pack)
    setDownloadState(pack, OfflineRegionDownloadState.INACTIVE)
  }

  override suspend fun delete(pack: OfflinePack) {
    requireOwned(pack)
    runOperation(
      description = "delete offline pack ${pack.regionId}",
      start = { it.startDeleteOfflineRegion(pack.regionId) },
      finish = { _, _ ->
        packsById.remove(pack.regionId)
        publishPacks()
      },
    )
  }

  override suspend fun invalidate(pack: OfflinePack) {
    requireOwned(pack)
    runOperation(
      description = "invalidate offline pack ${pack.regionId}",
      start = { it.startInvalidateOfflineRegion(pack.regionId) },
      finish = { _, _ -> },
    )
  }

  override suspend fun invalidateAmbientCache() {
    runAmbientCacheOperation("invalidate the ambient cache", AmbientCacheOperation.INVALIDATE)
  }

  override suspend fun clearAmbientCache() {
    runAmbientCacheOperation("clear the ambient cache", AmbientCacheOperation.CLEAR)
  }

  override suspend fun setMaximumAmbientCacheSize(size: Long) {
    // Lowering the budget evicts ambient resources to fit; offline packs are left alone.
    cacheBudgetMutex.withLock {
      runOperation(
        description = "set the maximum ambient cache size to $size bytes",
        start = { it.startSetMaximumAmbientCacheSize(size) },
        finish = { _, _ -> },
      )
    }
  }

  /** Stops this manager's owner thread. */
  internal fun close(timeoutMillis: Long = 30_000): Boolean {
    runtime.shutdown()
    return runtime.awaitStopped(timeoutMillis)
  }

  override fun setTileCountLimit(limit: Long) {
    // maplibre-native-ffi does not expose mbgl's setOfflineMapboxTileCountLimit; it applies only to
    // canonical Mapbox tile URLs. MapLibre's own limit still reports as TileLimitExceeded.
    logger.i {
      "Ignoring setTileCountLimit($limit) on this platform; MapLibre's own offline tile count limit " +
        "applies, and it counts only Mapbox-hosted tiles"
    }
  }

  private fun requireOwned(pack: OfflinePack) {
    require(pack.owner === this) { "The offline pack belongs to a different manager" }
  }

  /** Backs [OfflinePack.setMetadata]; the pack itself holds no native state. */
  override suspend fun updateMetadata(pack: OfflinePack, metadata: ByteArray) {
    val ffiMetadata = metadata.copyOf()
    runOperation(
      description = "update the metadata of offline pack ${pack.regionId}",
      start = { it.startUpdateOfflineRegionMetadata(pack.regionId, ffiMetadata) },
      finish = { nativeRuntime, handle ->
        // Native echoes back what it stored, which is what the pack should show.
        pack.metadataState.value =
          nativeRuntime.takeUpdateOfflineRegionMetadataResult(handle).metadata.copyOf()
      },
    )
  }

  // region owner-thread bookkeeping

  /**
   * Adopts a region MapLibre reported, or returns null when its definition cannot be represented.
   * Runs on the owner thread; there is at most one [OfflinePack] per region.
   */
  private fun registerRegion(info: OfflineRegionInfo): OfflinePack? {
    val existing = packsById[info.id]
    if (existing != null) return existing

    val definition = info.definition.toOfflinePackDefinition(logger) ?: return null
    val pack = OfflinePack(this, info.id, definition, info.metadata.copyOf())
    packsById[info.id] = pack
    publishPacks()

    // Status events only arrive for observed regions, and an unreported pack reads as Unknown.
    observe(info.id)
    refreshStatus(info.id)
    return pack
  }

  private fun setDownloadState(pack: OfflinePack, state: OfflineRegionDownloadState) {
    val accepted =
      submit(
        description = "change the download state of offline pack ${pack.regionId}",
        start = { it.startSetOfflineRegionDownloadState(pack.regionId, state) },
        // mbgl's setState early-returns when the state is unchanged, emitting no status event, so
        // the status is read back explicitly.
        finish = { _, _ -> refreshStatus(pack.regionId) },
      )
    if (!accepted) {
      logger.w { "Cannot change the download state of pack ${pack.regionId}: manager disposed" }
    }
  }

  private fun observe(regionId: Long) {
    submit(
      description = "observe offline pack $regionId",
      start = { it.startSetOfflineRegionObserved(regionId, true) },
      finish = { _, _ -> },
    )
  }

  private fun refreshStatus(regionId: Long) {
    submit(
      description = "read the status of offline pack $regionId",
      start = { it.startOfflineRegionStatus(regionId) },
      finish = { nativeRuntime, handle ->
        publishProgress(
          regionId,
          nativeRuntime.takeOfflineRegionStatusResult(handle).toDownloadProgress(logger),
        )
      },
    )
  }

  private suspend fun runAmbientCacheOperation(
    description: String,
    operation: AmbientCacheOperation,
  ) {
    runOperation(
      description = description,
      start = { it.startAmbientCacheOperation(operation) },
      finish = { _, _ -> },
    )
  }

  // endregion

  // region event pump callbacks

  /** Called on the owner thread for every runtime event that is not an operation completion. */
  private fun handleEvent(event: RuntimeEvent) {
    when (event.type) {
      RuntimeEventType.OFFLINE_REGION_STATUS_CHANGED -> {
        val payload = event.payload as? RuntimeEventPayload.OfflineRegionStatusChanged ?: return
        publishProgress(payload.regionId, payload.status.toDownloadProgress(logger))
      }

      RuntimeEventType.OFFLINE_REGION_TILE_COUNT_LIMIT_EXCEEDED -> {
        val payload = event.payload as? RuntimeEventPayload.OfflineRegionTileCountLimit ?: return
        logger.w { "Offline pack ${payload.regionId} hit the tile limit of ${payload.limit}" }
        publishProgress(payload.regionId, DownloadProgress.TileLimitExceeded(payload.limit))
      }

      RuntimeEventType.OFFLINE_REGION_RESPONSE_ERROR -> {
        val payload = event.payload as? RuntimeEventPayload.OfflineRegionResponseError ?: return
        val reason = payload.reason.toDownloadErrorReason()
        val message = event.message.ifBlank { "MapLibre could not download an offline resource" }
        logger.e { "Offline pack ${payload.regionId} failed ($reason): $message" }
        publishProgress(payload.regionId, DownloadProgress.Error(reason, message))
      }

      else ->
        // Event types are value classes over Int, so an FFI upgrade can deliver a type this build
        // has never seen.
        logger.v { "Ignoring MapLibre event ${event.type} on the offline runtime" }
    }
  }

  private fun publishProgress(regionId: Long, progress: DownloadProgress) {
    val pack = packsById[regionId]
    if (pack == null) {
      logger.v { "Ignoring progress for offline region $regionId, which has no pack" }
      return
    }
    pack.progressState.value = progress
  }

  private fun publishPacks() {
    // Snapshotted so Compose never sees the mutable map behind it.
    packsState.value = packsById.values.toSet()
  }

  // endregion

  // region operation plumbing

  /**
   * Starts an offline operation on the owner thread and reports its outcome there.
   *
   * [start] and [finish] both run on the owner thread: the first when the task is dequeued, the
   * second when the runtime reports that this operation's id completed. Returns false when the
   * manager is already disposed, in which case [onResult] is not called.
   */
  private fun <T, R> submit(
    description: String,
    start: (RuntimeHandle) -> OfflineOperationHandle<T>,
    finish: (RuntimeHandle, OfflineOperationHandle<T>) -> R,
    isCancelled: () -> Boolean = { false },
    onStarted: (OfflineOperationHandle<T>) -> Unit = {},
    onResult: (Result<R>) -> Unit = { result ->
      result.onFailure { logger.e(it) { "Failed to $description" } }
    },
  ): Boolean {
    return runtime.post(
      task = { nativeRuntime ->
        val handle = start(nativeRuntime)
        // Operation id is the only thing correlating a completion event with its in-flight handle.
        runtime.register(
          description = description,
          handle = handle,
          complete = { completedRuntime, event ->
            val result =
              try {
                failOnNativeError(description, event)
                Result.success(finish(completedRuntime, handle))
              } catch (error: Throwable) {
                Result.failure(error.toOfflineManagerException(description))
              }
            onResult(result)
          },
          discard = { reason -> onResult(Result.failure(reason)) },
        )
        onStarted(handle)
      },
      reject = { error -> onResult(Result.failure(error.toOfflineManagerException(description))) },
      isCancelled = isCancelled,
    )
  }

  /** The suspending form of [submit], cancellable down to the native operation. */
  private suspend fun <T, R> runOperation(
    description: String,
    start: (RuntimeHandle) -> OfflineOperationHandle<T>,
    finish: (RuntimeHandle, OfflineOperationHandle<T>) -> R,
  ): R = suspendCancellableCoroutine { continuation ->
    val accepted =
      submit(
        description = description,
        start = start,
        finish = finish,
        isCancelled = { !continuation.isActive },
        onStarted = { handle ->
          // Cancelling must leave nothing registered; discard drops and closes on the owner thread.
          continuation.invokeOnCancellation { runtime.discard(handle) }
        },
        // Resuming an already-cancelled continuation would report the failure to the caller's
        // exception handler instead of dropping it.
        onResult = { result -> if (continuation.isActive) continuation.resumeWith(result) },
      )
    if (!accepted) {
      continuation.resumeWithException(
        OfflineManagerException("Cannot $description: the offline manager has been disposed")
      )
    }
  }

  private fun failOnNativeError(description: String, event: RuntimeEvent) {
    val payload = event.payload as? RuntimeEventPayload.OfflineOperationCompleted ?: return
    if (payload.resultStatus == MaplibreStatus.OK.nativeCode) return
    val message = event.message.ifBlank { "status ${payload.resultStatus}" }
    throw OfflineManagerException("Failed to $description: $message")
  }

  private fun Throwable.toOfflineManagerException(description: String): Throwable =
    when (this) {
      is OfflineManagerException,
      is CancellationException -> this
      is MaplibreException -> {
        // OfflineManagerException carries no cause, so the native detail is logged before it is
        // flattened into the message.
        logger.d(this) { "Native failure while trying to $description" }
        val detail = diagnostic.ifBlank { message.orEmpty() }
        OfflineManagerException("Failed to $description: $detail")
      }
      else -> {
        logger.d(this) { "Failure while trying to $description" }
        OfflineManagerException(
          "Failed to $description: ${message ?: this::class.simpleName.orEmpty()}"
        )
      }
    }
  // endregion
}
