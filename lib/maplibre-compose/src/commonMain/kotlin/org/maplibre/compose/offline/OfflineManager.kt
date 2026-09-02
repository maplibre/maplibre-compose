package org.maplibre.compose.offline

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

internal class RuntimeBoundOfflineManager(
  private val delegate: OfflineManager,
  private val requireRuntimeOpen: () -> Unit,
) : OfflineManager {
  override val packs: Set<OfflinePack>
    get() = delegate.packs.onEach(::bindToRuntime)

  override suspend fun create(
    definition: OfflinePackDefinition,
    metadata: ByteArray,
  ): OfflinePack {
    requireRuntimeOpen()
    return bindToRuntime(delegate.create(definition, metadata))
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

  override suspend fun invalidateAmbientCache() {
    requireRuntimeOpen()
    delegate.invalidateAmbientCache()
  }

  override suspend fun clearAmbientCache() {
    requireRuntimeOpen()
    delegate.clearAmbientCache()
  }

  override suspend fun setMaximumAmbientCacheSize(size: Long) {
    requireRuntimeOpen()
    delegate.setMaximumAmbientCacheSize(size)
  }

  private fun bindToRuntime(pack: OfflinePack): OfflinePack = pack.bindToRuntime(requireRuntimeOpen)
}

internal object UnsupportedOfflineManager : OfflineManager {
  override val packs: Set<OfflinePack> = emptySet()

  override suspend fun create(
    definition: OfflinePackDefinition,
    metadata: ByteArray,
  ): OfflinePack = unsupportedOfflinePacks()

  override fun resume(pack: OfflinePack): Unit = unsupportedOfflinePacks()

  override fun pause(pack: OfflinePack): Unit = unsupportedOfflinePacks()

  override suspend fun delete(pack: OfflinePack): Unit = unsupportedOfflinePacks()

  override suspend fun invalidate(pack: OfflinePack): Unit = unsupportedOfflinePacks()

  override suspend fun invalidateAmbientCache(): Unit = unsupportedAmbientCacheManagement()

  override suspend fun clearAmbientCache(): Unit = unsupportedAmbientCacheManagement()

  override suspend fun setMaximumAmbientCacheSize(size: Long): Unit =
    unsupportedAmbientCacheManagement()

  private fun unsupportedOfflinePacks(): Nothing =
    throw UnsupportedOperationException("This map runtime does not support offline packs")

  private fun unsupportedAmbientCacheManagement(): Nothing =
    throw UnsupportedOperationException(
      "This map runtime does not support ambient-cache management"
    )
}
