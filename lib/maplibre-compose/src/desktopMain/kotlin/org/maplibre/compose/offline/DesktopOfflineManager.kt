package org.maplibre.compose.offline

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import co.touchlab.kermit.Logger
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.maplibre.compose.desktop.DesktopRuntimeOptions
import org.maplibre.compose.desktop.LocalDesktopRuntimeOptions
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

@Composable
public actual fun rememberOfflineManager(): OfflineManager {
  val options = LocalDesktopRuntimeOptions.current
  val density = LocalDensity.current.density
  val manager = remember(options) { DesktopOfflineManager.forOptions(options) }
  // Packs record the density they were created at, because a downloaded raster tile is either the
  // right resolution for this display or it is not; there is no rescaling it later.
  SideEffect { manager.pixelRatio = density }
  return manager
}

/**
 * The desktop [OfflineManager], backed by a MapLibre runtime of its own.
 *
 * One instance per [DesktopRuntimeOptions], kept for the life of the process. Sharing is necessary
 * because every `rememberOfflineManager()` call site would otherwise be another thread, another
 * runtime, and another view of the same database.
 *
 * They are deliberately never disposed. mbgl holds download state in memory only, so closing the
 * runtime destroys the in-flight downloads without an event — a user who starts a download and then
 * navigates away from the screen that composed the manager would silently lose it, and the pack
 * would come back reporting paused. Android and iOS are process singletons for the same reason, and
 * matching them keeps offline behaving the same everywhere. The cost is one idle thread per
 * distinct options value, which is bounded by configuration rather than by composition.
 */
internal class DesktopOfflineManager(private val options: DesktopRuntimeOptions) : OfflineManager {

  internal companion object {
    private val instances = mutableMapOf<DesktopRuntimeOptions, DesktopOfflineManager>()

    fun forOptions(options: DesktopRuntimeOptions): DesktopOfflineManager =
      synchronized(instances) { instances.getOrPut(options) { DesktopOfflineManager(options) } }
  }

  private val logger = Logger.withTag("maplibre-compose")

  /** Publishes to Compose state on the UI thread; native results arrive on the owner thread. */
  private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

  private val packsState = mutableStateOf(emptySet<OfflinePack>())

  /** Owner-thread state: the packs this manager has seen, keyed by native region id. */
  private val packsById = mutableMapOf<Long, OfflinePack>()

  @Volatile internal var pixelRatio: Float = 1f

  private val runtime = DesktopOfflineRuntime(options, logger, ::handleEvent)

  override val packs: Set<OfflinePack>
    get() = packsState.value

  init {
    runtime.start()
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

  override suspend fun create(definition: OfflinePackDefinition, metadata: ByteArray): OfflinePack {
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
    setDownloadState(pack, OfflineRegionDownloadState.ACTIVE)
  }

  override fun pause(pack: OfflinePack) {
    setDownloadState(pack, OfflineRegionDownloadState.INACTIVE)
  }

  override suspend fun delete(pack: OfflinePack) {
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
    // TODO(maplibre-native-ffi): there is no runtime call for mbgl's
    // DatabaseFileSource::setMaximumAmbientCacheSize; RuntimeOptions.maximumCacheSize fixes the
    // limit when the runtime is created and nothing can change it afterwards. Recreating the
    // runtime here is not the same operation: it would stop every download in progress and drop
    // the observers the live packs depend on.
    val configured = options.maximumCacheSizeBytes
    if (configured == null) {
      // The runtime was created with MapLibre's own default and there is no way to read it back,
      // so this cannot be verified as satisfied. Warn rather than throw: cross-platform code
      // routinely calls this at startup, and it succeeds on Android and iOS.
      logger.w {
        "Ignoring setMaximumAmbientCacheSize($size): the desktop ambient cache size is fixed when " +
          "the runtime is created. Set DesktopRuntimeOptions(maximumCacheSizeBytes = $size) " +
          "through LocalDesktopRuntimeOptions to control it."
      }
      return
    }
    if (size == configured) return
    throw OfflineManagerException(
      "The ambient cache size is fixed at $configured bytes for this runtime and cannot be " +
        "changed to $size afterwards. Provide " +
        "DesktopRuntimeOptions(maximumCacheSizeBytes = $size) through LocalDesktopRuntimeOptions " +
        "instead."
    )
  }

  override fun setTileCountLimit(limit: Long) {
    // TODO(maplibre-native-ffi): there is no runtime call for mbgl's
    // setOfflineMapboxTileCountLimit, so desktop downloads keep MapLibre's built-in limit. The
    // limit is still observed: exceeding it arrives as OFFLINE_REGION_TILE_COUNT_LIMIT_EXCEEDED
    // and becomes DownloadProgress.TileLimitExceeded.
    logger.w {
      "Desktop cannot set the offline tile count limit to $limit; MapLibre's own limit applies"
    }
  }

  /** Backs [OfflinePack.setMetadata]; the pack itself holds no native state. */
  internal suspend fun updateMetadata(pack: OfflinePack, metadata: ByteArray) {
    val ffiMetadata = metadata.copyOf()
    runOperation(
      description = "update the metadata of offline pack ${pack.regionId}",
      start = { it.startUpdateOfflineRegionMetadata(pack.regionId, ffiMetadata) },
      finish = { nativeRuntime, handle ->
        // Native echoes back what it stored, which is what the pack should show.
        val stored = nativeRuntime.takeUpdateOfflineRegionMetadataResult(handle).metadata.copyOf()
        publish { pack.metadataState.value = stored }
      },
    )
  }

  // ───────────────────────────── owner-thread bookkeeping ─────────────────────────────

  /**
   * Adopts a region MapLibre reported, or returns null when its definition cannot be represented.
   *
   * Runs on the owner thread. There is at most one [OfflinePack] per region, because only the
   * newest would receive updates to its state.
   */
  private fun registerRegion(info: OfflineRegionInfo): OfflinePack? {
    val existing = packsById[info.id]
    if (existing != null) return existing

    val definition = info.definition.toOfflinePackDefinition(logger) ?: return null
    val pack = OfflinePack(this, info.id, definition, info.metadata.copyOf())
    packsById[info.id] = pack
    publishPacks()

    // Status events only arrive for observed regions, and a pack that has never been told anything
    // reads as Unknown, so both are set up as soon as the pack exists.
    observe(info.id)
    refreshStatus(info.id)
    return pack
  }

  private fun setDownloadState(pack: OfflinePack, state: OfflineRegionDownloadState) {
    val accepted =
      submit(
        description = "change the download state of offline pack ${pack.regionId}",
        start = { it.startSetOfflineRegionDownloadState(pack.regionId, state) },
        // mbgl's setState does deliver a status event on every real transition, but it
        // early-returns when the state is unchanged — pausing an already-inactive or just-finished
        // pack produces nothing. Reading the status back covers that case, so a paused pack never
        // goes on claiming it is downloading.
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

  // ───────────────────────────── event pump callbacks ─────────────────────────────

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
        // Event types are value classes over Int rather than enums, so an FFI upgrade can deliver
        // a type this build has never seen, and a runtime without maps still reports map events.
        // Logging beats failing.
        logger.v { "Ignoring MapLibre event ${event.type} on the offline runtime" }
    }
  }

  /**
   * How many progress updates each region has produced, stamped on the owner thread.
   *
   * Two writers reach the same Compose state: the status events MapLibre pushes, and the explicit
   * reads [refreshStatus] makes after a state change. Both hop to the UI dispatcher, which does not
   * preserve the order they were produced in, so a resume could publish a stale value on top of a
   * fresher one and leave it there until the next event. The sequence number is what makes the
   * later value win.
   */
  private val progressSequenceByRegion = mutableMapOf<Long, Long>()

  private val publishedProgressSequence = mutableMapOf<Long, Long>()

  private fun publishProgress(regionId: Long, progress: DownloadProgress) {
    val pack = packsById[regionId]
    if (pack == null) {
      logger.v { "Ignoring progress for offline region $regionId, which has no pack" }
      return
    }
    val sequence = (progressSequenceByRegion[regionId] ?: 0L) + 1L
    progressSequenceByRegion[regionId] = sequence
    publish {
      // Read and written only on the UI dispatcher, which is single-threaded, so this needs no
      // lock of its own.
      if (sequence > (publishedProgressSequence[regionId] ?: 0L)) {
        publishedProgressSequence[regionId] = sequence
        pack.progressState.value = progress
      }
    }
  }

  private fun publishPacks() {
    // Snapshotted on the owner thread so Compose never sees the mutable map behind it.
    val snapshot = packsById.values.toSet()
    publish { packsState.value = snapshot }
  }

  private fun publish(update: () -> Unit) {
    uiScope.launch { update() }
  }

  // ───────────────────────────── operation plumbing ─────────────────────────────

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
    onStarted: (OfflineOperationHandle<T>) -> Unit = {},
    onResult: (Result<R>) -> Unit = { result ->
      result.onFailure { logger.e(it) { "Failed to $description" } }
    },
  ): Boolean =
    runtime.post(
      task = { nativeRuntime ->
        val handle = start(nativeRuntime)
        // Correlation is by operation id: the completion event carries the id this handle was
        // given, and nothing else identifies which of several in-flight operations finished.
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
    )

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
        onStarted = { handle ->
          // Cancelling must leave nothing registered: the operation is dropped and its handle
          // closed on the owner thread, so neither outlives the caller.
          continuation.invokeOnCancellation { runtime.discard(handle) }
        },
        // A cancelled continuation has already been completed; resuming it again would report the
        // failure to the caller's exception handler instead of dropping it.
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
}
