@file:OptIn(org.maplibre.compose.util.ExperimentalMaplibreComposeApi::class)

package org.maplibre.compose.offline

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.io.files.Path
import org.maplibre.compose.map.MapRuntime
import org.maplibre.compose.util.ExperimentalMaplibreComposeApi
import org.maplibre.compose.util.formatToString

/** The offline packs and ambient cache that belong to this runtime. */
public val MapRuntime.offlineStorage: OfflineStorage
  // Every runtime that callers can reach on MapLibre Native has storage. The IDE preview's
  // runtime has none, but rememberMapState keeps it internal.
  get() =
    checkNotNull(boundOfflineStorage as OfflineStorage?) {
      "This map runtime has no offline storage"
    }

/**
 * Manages the offline packs and ambient cache that belong to one map runtime.
 *
 * Maps and snapshotters of the same runtime load resources from this storage's database when it has
 * them, so a region that a downloaded pack covers displays without a network connection.
 *
 * The suspending functions wait while [state] is [OfflineStorageState.Loading]. If initialization
 * fails, they throw [OfflineStorageState.Failed.cause].
 */
public sealed interface OfflineStorage {

  /**
   * Initialization and the current packs. Constructing a runtime never waits for its database.
   *
   * The state starts as [OfflineStorageState.Loading] while the storage opens its database and
   * lists the packs stored there, then becomes [OfflineStorageState.Ready] or
   * [OfflineStorageState.Failed].
   */
  public val state: StateFlow<OfflineStorageState>

  /**
   * Creates a paused offline pack for [definition]. Call [resume] to start its download.
   *
   * @throws OfflineStorageException if the operation failed.
   */
  public suspend fun create(
    definition: OfflinePackDefinition,
    metadata: ByteArray = ByteArray(0),
  ): OfflinePack

  /**
   * Resumes the download of [pack]. Returns without waiting; [OfflinePack.downloadProgress] reports
   * the new status.
   *
   * The database does not store whether a pack was downloading. After the runtime is created again,
   * such as in a later app launch, an incomplete pack is paused until this is called.
   */
  public fun resume(pack: OfflinePack)

  /** Pauses the download of [pack]. */
  public fun pause(pack: OfflinePack)

  /**
   * Unregisters [pack] and permits the cache to remove resources that no remaining pack needs.
   *
   * @throws OfflineStorageException if the operation failed.
   */
  public suspend fun delete(pack: OfflinePack)

  /**
   * Checks the resources in [pack] against the server and downloads changed resources.
   *
   * @throws OfflineStorageException if the operation failed.
   */
  public suspend fun invalidate(pack: OfflinePack)

  /**
   * Merges the offline packs and their resources from [databaseFile] into this storage's database.
   *
   * The merge does not modify the source database. Ambient-cache resources are not imported.
   *
   * @param databaseFile Must identify a readable MapLibre offline database with the same schema
   *   version as this runtime's database.
   * @return The set of packs represented by the source database. It includes an existing pack when
   *   the source contains the same definition and metadata. Imported packs can be incomplete when
   *   the source database does not contain every required resource.
   * @throws OfflineStorageException if the operation failed.
   */
  // Exposes kotlinx-io Path, which is not yet stable.
  @ExperimentalMaplibreComposeApi
  public suspend fun mergeDatabase(databaseFile: Path): Set<OfflinePack>

  /**
   * Checks ambient-cache resources against the server and downloads changed resources.
   *
   * @throws OfflineStorageException if the operation failed.
   */
  public suspend fun invalidateAmbientCache()

  /**
   * Deletes ambient-cache resources that no offline pack needs.
   *
   * @throws OfflineStorageException if the operation failed.
   */
  public suspend fun clearAmbientCache()

  /**
   * Limits how many bytes of ambient-cache resources the database keeps.
   *
   * Lowering the limit deletes the least recently used ambient-cache resources until the ambient
   * cache fits. Resources that an offline pack needs are never deleted and do not count toward the
   * limit.
   *
   * @param sizeBytes The maximum ambient-cache size in bytes. Zero keeps no ambient-cache
   *   resources. Must not be negative.
   * @throws IllegalArgumentException if [sizeBytes] is negative.
   * @throws OfflineStorageException if the operation failed.
   */
  public suspend fun setMaximumAmbientCacheSize(sizeBytes: Long)
}

/**
 * The initialization result and current contents of an [OfflineStorage].
 *
 * Values may be added in minor releases; use an `else` branch when matching.
 */
@Immutable
public sealed interface OfflineStorageState {
  /** Initialization has not finished. */
  public data object Loading : OfflineStorageState

  /**
   * Initialization succeeded, with the current [packs].
   *
   * This state can remain after the runtime closes; it does not indicate whether the storage
   * accepts operations.
   *
   * @property packs The current packs. Includes the packs stored in the database before this
   *   runtime started, except packs whose definition this library cannot represent, which are
   *   logged and left out. A new state replaces it when the set of packs changes.
   */
  public data class Ready internal constructor(public val packs: Set<OfflinePack>) :
    OfflineStorageState

  /**
   * Initialization failed, or the storage closed before initialization finished. The storage
   * accepts no operations in this state.
   *
   * @property cause Why initialization did not succeed.
   */
  public data class Failed internal constructor(public val cause: Throwable) : OfflineStorageState
}

/**
 * Keeps [OfflineStorageState] open: callers' `when` needs an `else` branch. The library never
 * reports it.
 */
internal data object UnspecifiedOfflineStorageState : OfflineStorageState

internal suspend fun OfflineStorage.awaitReady() {
  when (val current = state.first { it !is OfflineStorageState.Loading }) {
    is OfflineStorageState.Ready -> Unit
    is OfflineStorageState.Failed -> throw current.cause
    OfflineStorageState.Loading -> error("Initialization has not completed")
    UnspecifiedOfflineStorageState -> error("UnspecifiedOfflineStorageState is never reported")
  }
}

/** The runtime-independent part of an [OfflineStorage] implementation. */
internal interface OfflineStorageBackend : OfflineStorage, AutoCloseable {
  /** Rejects startup and new work immediately; native resource release completes separately. */
  override fun close()

  /**
   * Installs the check that every pack operation runs before touching the backend. The runtime
   * calls this once, before it hands the storage to callers.
   */
  fun bindToRuntime(requireRuntimeOpen: () -> Unit)
}

/** Checks that the runtime is open before each operation. The runtime closes it. */
internal class RuntimeBoundOfflineStorage(
  private val delegate: OfflineStorageBackend,
  private val requireRuntimeOpen: () -> Unit,
) : OfflineStorage, AutoCloseable {
  init {
    delegate.bindToRuntime(requireRuntimeOpen)
  }

  override fun close() = delegate.close()

  override val state: StateFlow<OfflineStorageState>
    get() = delegate.state

  override suspend fun create(
    definition: OfflinePackDefinition,
    metadata: ByteArray,
  ): OfflinePack {
    requireRuntimeOpen()
    return delegate.create(definition, metadata)
  }

  override fun resume(pack: OfflinePack) {
    requireRuntimeOpen()
    delegate.resume(pack)
  }

  override fun pause(pack: OfflinePack) {
    requireRuntimeOpen()
    delegate.pause(pack)
  }

  override suspend fun delete(pack: OfflinePack) {
    requireRuntimeOpen()
    delegate.delete(pack)
  }

  override suspend fun invalidate(pack: OfflinePack) {
    requireRuntimeOpen()
    delegate.invalidate(pack)
  }

  override suspend fun mergeDatabase(databaseFile: Path): Set<OfflinePack> {
    requireRuntimeOpen()
    return delegate.mergeDatabase(databaseFile)
  }

  override suspend fun invalidateAmbientCache() {
    requireRuntimeOpen()
    delegate.invalidateAmbientCache()
  }

  override suspend fun clearAmbientCache() {
    requireRuntimeOpen()
    delegate.clearAmbientCache()
  }

  override suspend fun setMaximumAmbientCacheSize(sizeBytes: Long) {
    require(sizeBytes >= 0) { "sizeBytes must not be negative, was $sizeBytes" }
    requireRuntimeOpen()
    delegate.setMaximumAmbientCacheSize(sizeBytes)
  }

  // Packs are counted, not listed: a pack definition's style URL can contain an access token.
  override fun toString(): String =
    when (val current = state.value) {
      OfflineStorageState.Loading -> formatToString("OfflineStorage", "state" to "Loading")
      is OfflineStorageState.Ready ->
        formatToString("OfflineStorage", "state" to "Ready", "packs" to current.packs.size)
      is OfflineStorageState.Failed ->
        formatToString("OfflineStorage", "state" to "Failed", "cause" to current.cause)
      UnspecifiedOfflineStorageState -> formatToString("OfflineStorage", "state" to current)
    }
}
