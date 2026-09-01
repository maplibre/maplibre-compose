package org.maplibre.compose.offline

import org.maplibre.compose.map.MapRuntimeCapabilities

/** Manages the offline packs and ambient cache that belong to one map runtime. */
public interface OfflineManager {

  /** All offline packs registered with this manager. Backed by Compose snapshot state. */
  public val packs: Set<OfflinePack>

  /**
   * Creates a paused offline pack for [definition]. Call [resume] to start its download.
   *
   * @throws UnsupportedOperationException if the runtime does not support offline packs.
   * @throws OfflineManagerException if the operation failed.
   */
  public suspend fun create(
    definition: OfflinePackDefinition,
    metadata: ByteArray = ByteArray(0),
  ): OfflinePack

  /**
   * Resumes the download of [pack].
   *
   * @throws UnsupportedOperationException if the runtime does not support offline packs.
   */
  public fun resume(pack: OfflinePack)

  /**
   * Pauses the download of [pack].
   *
   * @throws UnsupportedOperationException if the runtime does not support offline packs.
   */
  public fun pause(pack: OfflinePack)

  /**
   * Unregisters [pack] and permits the cache to remove resources that no remaining pack needs.
   *
   * @throws UnsupportedOperationException if the runtime does not support offline packs.
   * @throws OfflineManagerException if the operation failed.
   */
  public suspend fun delete(pack: OfflinePack)

  /**
   * Checks the resources in [pack] against the server and downloads changed resources.
   *
   * @throws UnsupportedOperationException if the runtime does not support offline packs.
   * @throws OfflineManagerException if the operation failed.
   */
  public suspend fun invalidate(pack: OfflinePack)

  /**
   * Checks ambient-cache resources against the server and downloads changed resources.
   *
   * @throws UnsupportedOperationException if the runtime does not support ambient-cache management.
   * @throws OfflineManagerException if the operation failed.
   */
  public suspend fun invalidateAmbientCache()

  /**
   * Deletes ambient-cache resources that no offline pack needs.
   *
   * @throws UnsupportedOperationException if the runtime does not support ambient-cache management.
   * @throws OfflineManagerException if the operation failed.
   */
  public suspend fun clearAmbientCache()

  /**
   * Sets the maximum ambient-cache size in bytes. A size of zero disables ambient caching.
   *
   * @throws UnsupportedOperationException if the runtime does not support ambient-cache management.
   * @throws OfflineManagerException if the operation failed.
   */
  public suspend fun setMaximumAmbientCacheSize(size: Long)
}

internal class CapabilityCheckedOfflineManager(
  private val capabilities: MapRuntimeCapabilities,
  private val delegate: OfflineManager,
  private val requireRuntimeOpen: () -> Unit,
) : OfflineManager {
  override val packs: Set<OfflinePack>
    get() =
      if (capabilities.supportsOfflinePacks) delegate.packs.onEach(::bindToRuntime) else emptySet()

  override suspend fun create(
    definition: OfflinePackDefinition,
    metadata: ByteArray,
  ): OfflinePack {
    requireOfflinePackOperation()
    return bindToRuntime(delegate.create(definition, metadata))
  }

  override fun resume(pack: OfflinePack) {
    requireOfflinePackOperation()
    delegate.resume(pack)
  }

  override fun pause(pack: OfflinePack) {
    requireOfflinePackOperation()
    delegate.pause(pack)
  }

  override suspend fun delete(pack: OfflinePack) {
    requireOfflinePackOperation()
    delegate.delete(pack)
  }

  override suspend fun invalidate(pack: OfflinePack) {
    requireOfflinePackOperation()
    delegate.invalidate(pack)
  }

  override suspend fun invalidateAmbientCache() {
    requireAmbientCacheOperation()
    delegate.invalidateAmbientCache()
  }

  override suspend fun clearAmbientCache() {
    requireAmbientCacheOperation()
    delegate.clearAmbientCache()
  }

  override suspend fun setMaximumAmbientCacheSize(size: Long) {
    requireAmbientCacheOperation()
    delegate.setMaximumAmbientCacheSize(size)
  }

  private fun bindToRuntime(pack: OfflinePack): OfflinePack = pack.bindToRuntime(requireRuntimeOpen)

  private fun requireOfflinePackOperation() {
    requireRuntimeOpen()
    if (!capabilities.supportsOfflinePacks) {
      throw UnsupportedOperationException("This map runtime does not support offline packs")
    }
  }

  private fun requireAmbientCacheOperation() {
    requireRuntimeOpen()
    if (!capabilities.supportsAmbientCacheManagement) {
      throw UnsupportedOperationException(
        "This map runtime does not support ambient-cache management"
      )
    }
  }
}

internal object EmptyOfflineManager : OfflineManager {
  override val packs: Set<OfflinePack> = emptySet()

  override suspend fun create(
    definition: OfflinePackDefinition,
    metadata: ByteArray,
  ): OfflinePack = unsupported()

  override fun resume(pack: OfflinePack): Unit = unsupported()

  override fun pause(pack: OfflinePack): Unit = unsupported()

  override suspend fun delete(pack: OfflinePack): Unit = unsupported()

  override suspend fun invalidate(pack: OfflinePack): Unit = unsupported()

  override suspend fun invalidateAmbientCache(): Unit = unsupported()

  override suspend fun clearAmbientCache(): Unit = unsupported()

  override suspend fun setMaximumAmbientCacheSize(size: Long): Unit = unsupported()

  private fun unsupported(): Nothing =
    error("The capability wrapper must reject unsupported offline operations")
}
