@file:OptIn(org.maplibre.compose.util.ExperimentalMaplibreComposeApi::class)

package org.maplibre.compose.offline

import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import org.maplibre.compose.mlnffi.MlnFfiLock
import org.maplibre.compose.mlnffi.MlnFfiRuntime
import org.maplibre.compose.mlnffi.normalizeMlnFfiPath
import org.maplibre.compose.mlnffi.withLock
import org.maplibre.compose.resource.toCommon
import org.maplibre.nativeffi.error.MaplibreException
import org.maplibre.nativeffi.generated.AmbientCacheOperation
import org.maplibre.nativeffi.generated.OfflineRegionDownloadState
import org.maplibre.nativeffi.generated.OfflineRegionInfo
import org.maplibre.nativeffi.generated.OfflineRegionStatus
import org.maplibre.nativeffi.generated.RuntimeEvent
import org.maplibre.nativeffi.generated.RuntimeEventPayload
import org.maplibre.nativeffi.generated.RuntimeEventType
import org.maplibre.nativeffi.generated.RuntimeHandle

/**
 * The MapLibre Native FFI offline storage that belongs to one map runtime. Every operation is one
 * native runtime operation, run in submission order, whose result completes the caller's wait.
 */
internal class MlnFfiOfflineStorage(private val owner: MlnFfiRuntime) :
  OfflineStorageBackend, OfflinePackOwner {

  private val options = owner.options
  private val logger = options.logger
  private val scope = CoroutineScope(SupervisorJob() + owner.mainDispatcher)

  // Not cancelled by close: an operation native accepted still updates the packs when it finishes,
  // and the runtime waits for it before releasing.
  private val operations = owner.acceptedWorkScope

  private val storageState = MutableStateFlow<OfflineStorageState>(OfflineStorageState.Loading)

  /** The packs this storage has seen, keyed by native region id, under [lock]. */
  private val packsById = mutableMapOf<Long, OfflinePack>()
  private val lock = MlnFfiLock()
  @Volatile private var open = true

  @OptIn(ExperimentalAtomicApi::class) private val runtimeGuard = AtomicReference<() -> Unit> {}

  override val state: StateFlow<OfflineStorageState> = storageState.asStateFlow()

  init {
    owner.onRuntimeEvent = ::handleEvent
    val initialCacheSize = options.maximumCacheSizeBytes
    if (initialCacheSize != null && initialCacheSize < 0) {
      failStartup(OfflineStorageException("The maximum ambient cache size must not be negative"))
    } else
      scope.launch {
        try {
          val runtime = owner.awaitRuntime()
          coroutineScope {
            val cacheSize =
              async(start = CoroutineStart.UNDISPATCHED) {
                initialCacheSize?.let { size ->
                  run("set the initial maximum ambient cache size to $size bytes") {
                    runtime.setMaximumAmbientCacheSize(size.toULong())
                  }
                  logger?.d { "Ambient cache size set to $size bytes" }
                }
              }
            val regions =
              async(start = CoroutineStart.UNDISPATCHED) {
                run("list the offline packs") { runtime.listOfflineRegions() }
              }
            cacheSize.await()
            regions
              .await()
              .map { info ->
                async(start = CoroutineStart.UNDISPATCHED) {
                  runCatching { registerRegion(runtime, info) }
                    .onFailure { logger?.w(it) { "Ignoring offline region ${info.id}" } }
                }
              }
              .forEach { it.await() }
          }
          lock.withLock {
            storageState.compareAndSet(
              OfflineStorageState.Loading,
              OfflineStorageState.Ready(packsById.values.toSet()),
            )
          }
        } catch (error: Throwable) {
          failStartup(error)
        }
      }
  }

  @OptIn(ExperimentalAtomicApi::class)
  override fun bindToRuntime(requireRuntimeOpen: () -> Unit) {
    runtimeGuard.store(requireRuntimeOpen)
  }

  @OptIn(ExperimentalAtomicApi::class)
  override fun requireRuntimeOpen() {
    runtimeGuard.load()()
  }

  private fun failStartup(error: Throwable) {
    storageState.compareAndSet(OfflineStorageState.Loading, OfflineStorageState.Failed(error))
  }

  override suspend fun create(definition: OfflinePackDefinition, metadata: ByteArray): OfflinePack {
    val ffiDefinition = definition.toFfiRegionDefinition()
    val runtime = awaitRuntime()
    return finishing {
      val info =
        run("create an offline pack") {
          // Native copies the metadata at submission, so the caller keeps its array.
          runtime.createOfflineRegion(ffiDefinition, metadata)
        }
      registerRegion(runtime, info)
        ?: throw OfflineStorageException(
          "MapLibre created an offline region that this build cannot represent",
        )
    }
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
    val runtime = awaitRuntime()
    finishing {
      run("delete offline pack ${pack.regionId}") { runtime.deleteOfflineRegion(pack.regionId) }
      lock.withLock {
        packsById.remove(pack.regionId)
        publishPacks()
      }
    }
  }

  override suspend fun invalidate(pack: OfflinePack) {
    requireOwned(pack)
    val runtime = awaitRuntime()
    run("invalidate offline pack ${pack.regionId}") {
      runtime.invalidateOfflineRegion(pack.regionId)
    }
  }

  override suspend fun mergeDatabase(databaseFile: Path): Set<OfflinePack> {
    val sourceFile = normalizeMlnFfiPath(databaseFile)
    require(sourceFile != options.cacheFile) {
      "The source database must differ from this storage's database"
    }
    val runtime = awaitRuntime()
    return finishing {
      val merged =
        run("merge offline database $sourceFile") {
          runtime.mergeOfflineRegions(sourceFile.toString())
        }
      coroutineScope {
        merged
          .map { info ->
            async(start = CoroutineStart.UNDISPATCHED) { registerRegion(runtime, info) }
          }
          .mapNotNull { it.await() }
          .toSet()
      }
    }
  }

  override suspend fun invalidateAmbientCache() {
    runAmbientCacheOperation("invalidate the ambient cache", AmbientCacheOperation.INVALIDATE)
  }

  override suspend fun clearAmbientCache() {
    runAmbientCacheOperation("clear the ambient cache", AmbientCacheOperation.CLEAR)
  }

  override suspend fun setMaximumAmbientCacheSize(sizeBytes: Long) {
    // Lowering the budget evicts ambient resources to fit; offline packs are left alone.
    val runtime = awaitRuntime()
    run("set the maximum ambient cache size to $sizeBytes bytes") {
      require(sizeBytes >= 0) { "The maximum ambient cache size must not be negative" }
      runtime.setMaximumAmbientCacheSize(sizeBytes.toULong())
    }
  }

  /** Rejects further work immediately. Operations native already accepted still finish. */
  override fun close() {
    open = false
    // Background reads and status refreshes stop; a caller's own operation keeps its wait.
    scope.cancel()
    storageState.compareAndSet(
      OfflineStorageState.Loading,
      OfflineStorageState.Failed(OfflineStorageException("The offline storage is closed")),
    )
  }

  private fun requireOwned(pack: OfflinePack) {
    require(pack.owner === this) { "The offline pack belongs to a different offline storage" }
  }

  /** Backs [OfflinePack.setMetadata]; the pack itself holds no native state. */
  override suspend fun updateMetadata(pack: OfflinePack, metadata: ByteArray) {
    val runtime = awaitRuntime()
    finishing {
      val info =
        run("update the metadata of offline pack ${pack.regionId}") {
          runtime.updateOfflineRegionMetadata(pack.regionId, metadata)
        }
      // Native echoes back what it stored, which is what the pack should show.
      pack.metadataState.value = info.metadata.copyOf()
    }
  }

  // region bookkeeping

  /**
   * Adopts or reuses a region that MapLibre reported, or returns null when its definition cannot be
   * represented. There is at most one [OfflinePack] per region.
   */
  private suspend fun registerRegion(
    runtime: RuntimeHandle,
    info: OfflineRegionInfo,
  ): OfflinePack? =
    withContext(owner.mainDispatcher) {
      val existing = lock.withLock { packsById[info.id] }
      if (existing != null) {
        // An operation such as a database merge can change the resources of an existing region.
        refreshStatus(existing) { runtime.getOfflineRegionStatus(info.id) }
        return@withContext existing
      }

      val definition = info.definition.toOfflinePackDefinition(logger) ?: return@withContext null
      val pack = lock.withLock {
        packsById
          .getOrPut(info.id) {
            OfflinePack(this@MlnFfiOfflineStorage, info.id, definition, info.metadata.copyOf())
          }
          .also { publishPacks() }
      }

      // Submit observation and seed progress before returning: a caller can resume the download
      // immediately, and its progress must not be overwritten by a late initial status read.
      launchOperation("observe offline pack ${info.id}") {
        runtime.setOfflineRegionObserved(info.id, true)
      }
      refreshStatus(pack) { runtime.getOfflineRegionStatus(info.id) }
      pack
    }

  private fun setDownloadState(pack: OfflinePack, state: OfflineRegionDownloadState) {
    if (!open) {
      logger?.w { "Cannot change the download state of pack ${pack.regionId}: storage disposed" }
      return
    }
    scope.launch {
      val runtime = owner.awaitRuntime()
      launchOperation("change the download state of offline pack ${pack.regionId}") {
        runtime.setOfflineRegionDownloadState(pack.regionId, state)
      }
    }
  }

  internal suspend fun refreshStatus(
    pack: OfflinePack,
    start: () -> Deferred<OfflineRegionStatus>,
  ) {
    val revision = lock.withLock { pack.progressRevision }
    try {
      val status = run("read the status of offline pack ${pack.regionId}", start)
      lock.withLock {
        if (packsById[pack.regionId] === pack && pack.progressRevision == revision) {
          pack.progressRevision++
          pack.progressState.value = status.toDownloadProgress()
        }
      }
    } catch (error: Throwable) {
      if (error is CancellationException) throw error
      logger?.e(error) { "Failed to read the status of offline pack ${pack.regionId}" }
    }
  }

  private suspend fun runAmbientCacheOperation(
    description: String,
    operation: AmbientCacheOperation,
  ) {
    val runtime = awaitRuntime()
    run(description) { runtime.runAmbientCacheOperation(operation) }
  }

  // endregion

  // region events

  /** Called on main for every runtime event. */
  private fun handleEvent(event: RuntimeEvent) {
    if (!open) return
    when (event.type) {
      RuntimeEventType.OFFLINE_REGION_STATUS_CHANGED -> {
        val payload = (event.payload as? RuntimeEventPayload.OfflineRegionStatus)?.value ?: return
        publishProgress(payload.regionId, payload.status.toDownloadProgress())
      }

      RuntimeEventType.OFFLINE_REGION_TILE_COUNT_LIMIT_EXCEEDED -> {
        val payload =
          (event.payload as? RuntimeEventPayload.OfflineRegionTileCountLimit)?.value ?: return
        logger?.w { "Offline pack ${payload.regionId} hit the tile limit of ${payload.limit}" }
        publishProgress(
          payload.regionId,
          DownloadProgress.TileLimitExceeded(payload.limit.toLong()),
        )
      }

      RuntimeEventType.OFFLINE_REGION_RESPONSE_ERROR -> {
        val payload =
          (event.payload as? RuntimeEventPayload.OfflineRegionResponseError)?.value ?: return
        val reason = payload.reason.toCommon()
        val message = event.message.ifBlank { "MapLibre could not download an offline resource" }
        logger?.e { "Offline pack ${payload.regionId} failed (${reason.value}): $message" }
        publishProgress(payload.regionId, DownloadProgress.Error(reason, message))
      }

      else ->
        // Event types are open value classes, so an FFI upgrade can deliver a type this build has
        // never seen.
        logger?.d { "Ignoring MapLibre event ${event.type} in the offline storage" }
    }
  }

  private fun publishProgress(regionId: Long, progress: DownloadProgress) = lock.withLock {
    val pack = packsById[regionId]
    if (pack == null) {
      logger?.d { "Ignoring progress for offline region $regionId, which has no pack" }
      return@withLock
    }
    pack.progressRevision++
    pack.progressState.value = progress
  }

  /** Under [lock]. */
  private fun publishPacks() {
    // Snapshotted so Compose never sees the mutable map behind it.
    if (storageState.value is OfflineStorageState.Ready) {
      storageState.value = OfflineStorageState.Ready(packsById.values.toSet())
    }
  }

  // endregion

  // region operation plumbing

  /** Waits until startup has finished, and throws its failure, before a public operation. */
  private suspend fun awaitRuntime(): RuntimeHandle {
    check(open) { "The offline storage has been disposed" }
    val ready = storageState.first { it !is OfflineStorageState.Loading }
    if (ready is OfflineStorageState.Failed) throw ready.cause
    return owner.awaitRuntime()
  }

  /**
   * Starts one native operation and waits for its result. A caller cancelled before submission
   * starts nothing; once native accepted the operation, cancelling ends only the wait.
   */
  private suspend fun <T> run(description: String, start: () -> Deferred<T>): T {
    if (!open) {
      throw OfflineStorageException("Cannot $description: the offline storage has been disposed")
    }
    (storageState.value as? OfflineStorageState.Failed)?.let { throw it.cause }
    currentCoroutineContext().ensureActive()
    return try {
      start().await()
    } catch (error: Throwable) {
      throw error.toOfflineStorageException(description)
    }
  }

  /**
   * Runs [operation], which changes what the storage holds, to its end even when the caller stops
   * waiting, so the published packs follow what native did. A caller cancelled before it starts
   * starts nothing.
   */
  private suspend fun <T> finishing(operation: suspend CoroutineScope.() -> T): T {
    currentCoroutineContext().ensureActive()
    return operations.async(start = CoroutineStart.UNDISPATCHED, block = operation).await()
  }

  /** Starts an operation nobody waits on, logging its failure. */
  private fun launchOperation(description: String, start: () -> Deferred<Unit>) =
    if (!open) null
    else
      scope.launch(start = CoroutineStart.UNDISPATCHED) {
        try {
          run(description, start)
        } catch (error: Throwable) {
          if (error is CancellationException) throw error
          logger?.e(error) { "Failed to $description" }
        }
      }

  private fun Throwable.toOfflineStorageException(description: String): Throwable =
    when (this) {
      is OfflineStorageException,
      is CancellationException,
      is Error -> this
      is MaplibreException -> {
        logger?.d(this) { "Native failure while trying to $description" }
        val detail = diagnostic.ifBlank { message.orEmpty() }
        OfflineStorageException("Failed to $description: $detail", this)
      }
      else -> {
        logger?.d(this) { "Failure while trying to $description" }
        OfflineStorageException(
          "Failed to $description: ${message ?: this::class.simpleName.orEmpty()}",
          this,
        )
      }
    }
  // endregion
}
