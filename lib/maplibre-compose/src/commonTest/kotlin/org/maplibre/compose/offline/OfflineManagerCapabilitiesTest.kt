package org.maplibre.compose.offline

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.maplibre.compose.map.MapRuntimeCapabilities
import org.maplibre.compose.map.MapRuntimeClosedException
import org.maplibre.compose.map.MapRuntimeResources
import org.maplibre.compose.map.RuntimeImplementation
import org.maplibre.spatialk.geojson.BoundingBox

class OfflineManagerCapabilitiesTest {
  @Test
  fun offline_pack_operations_require_only_the_offline_pack_capability() = runTest {
    val backend = RecordingOfflineManager()
    val runtime =
      runtime(backend, supportsOfflinePacks = false, supportsAmbientCacheManagement = true)
    val manager = runtime.offlineManager

    assertEquals(emptySet(), manager.packs)
    assertFailsWith<UnsupportedOperationException> { manager.create(definition) }
    assertFailsWith<UnsupportedOperationException> { manager.resume(backend.pack) }
    assertFailsWith<UnsupportedOperationException> { manager.pause(backend.pack) }
    assertFailsWith<UnsupportedOperationException> { manager.delete(backend.pack) }
    assertFailsWith<UnsupportedOperationException> { manager.invalidate(backend.pack) }
    assertFailsWith<UnsupportedOperationException> { manager.setTileCountLimit(1) }
    assertEquals(emptyList(), backend.calls)

    manager.invalidateAmbientCache()
    manager.clearAmbientCache()
    manager.setMaximumAmbientCacheSize(1)
    assertEquals(listOf("invalidate ambient", "clear ambient", "set ambient size"), backend.calls)
    runtime.close()
  }

  @Test
  fun ambient_cache_operations_require_only_the_ambient_cache_capability() = runTest {
    val backend = RecordingOfflineManager()
    val runtime =
      runtime(backend, supportsOfflinePacks = true, supportsAmbientCacheManagement = false)
    val manager = runtime.offlineManager

    assertSame(backend.pack, manager.packs.single())
    assertSame(backend.createdPack, manager.create(definition))
    manager.resume(backend.pack)
    manager.pause(backend.pack)
    manager.delete(backend.pack)
    manager.invalidate(backend.pack)
    manager.setTileCountLimit(1)
    assertEquals(
      listOf("create", "resume", "pause", "delete", "invalidate", "set tile limit"),
      backend.calls,
    )

    assertFailsWith<UnsupportedOperationException> { manager.invalidateAmbientCache() }
    assertFailsWith<UnsupportedOperationException> { manager.clearAmbientCache() }
    assertFailsWith<UnsupportedOperationException> { manager.setMaximumAmbientCacheSize(1) }
    assertEquals(6, backend.calls.size)
    runtime.close()
  }

  @Test
  fun runtime_closure_rejects_every_offline_manager_operation() = runTest {
    val backend = RecordingOfflineManager()
    val releaseCleanup = CompletableDeferred<Unit>()
    val runtime =
      runtime(
        backend,
        supportsOfflinePacks = true,
        supportsAmbientCacheManagement = true,
        resources = MapRuntimeResources { releaseCleanup.await() },
      )
    val manager = runtime.offlineManager
    val retainedPack = manager.packs.single()
    val createdPack = manager.create(definition)
    backend.calls.clear()

    runtime.close()

    assertFailsWith<MapRuntimeClosedException> { manager.create(definition) }
    assertFailsWith<MapRuntimeClosedException> { manager.resume(backend.pack) }
    assertFailsWith<MapRuntimeClosedException> { manager.pause(backend.pack) }
    assertFailsWith<MapRuntimeClosedException> { manager.delete(backend.pack) }
    assertFailsWith<MapRuntimeClosedException> { manager.invalidate(backend.pack) }
    assertFailsWith<MapRuntimeClosedException> { manager.invalidateAmbientCache() }
    assertFailsWith<MapRuntimeClosedException> { manager.clearAmbientCache() }
    assertFailsWith<MapRuntimeClosedException> { manager.setMaximumAmbientCacheSize(1) }
    assertFailsWith<MapRuntimeClosedException> { manager.setTileCountLimit(1) }
    assertFailsWith<MapRuntimeClosedException> { retainedPack.setMetadata(byteArrayOf(1)) }
    assertFailsWith<MapRuntimeClosedException> { createdPack.setMetadata(byteArrayOf(1)) }
    assertEquals(emptyList(), backend.calls)

    releaseCleanup.complete(Unit)
    runtime.awaitClosed()
  }

  private fun runtime(
    backend: OfflineManager,
    supportsOfflinePacks: Boolean,
    supportsAmbientCacheManagement: Boolean,
    resources: MapRuntimeResources = MapRuntimeResources {},
  ) =
    RuntimeImplementation(
      platformOptions = null,
      resources = resources,
      logger = null,
      capabilities =
        MapRuntimeCapabilities(
          supportsOfflinePacks = supportsOfflinePacks,
          supportsAmbientCacheManagement = supportsAmbientCacheManagement,
        ),
      offlineManagerBackend = backend,
    )

  private class RecordingOfflineManager : OfflineManager {
    val calls = mutableListOf<String>()
    val pack = pack(regionId = 1)
    val createdPack = pack(regionId = 2)

    override val packs: Set<OfflinePack> = setOf(pack)

    override suspend fun create(
      definition: OfflinePackDefinition,
      metadata: ByteArray,
    ): OfflinePack = createdPack.also { calls += "create" }

    override fun resume(pack: OfflinePack) {
      calls += "resume"
    }

    override fun pause(pack: OfflinePack) {
      calls += "pause"
    }

    override suspend fun delete(pack: OfflinePack) {
      calls += "delete"
    }

    override suspend fun invalidate(pack: OfflinePack) {
      calls += "invalidate"
    }

    override suspend fun invalidateAmbientCache() {
      calls += "invalidate ambient"
    }

    override suspend fun clearAmbientCache() {
      calls += "clear ambient"
    }

    override suspend fun setMaximumAmbientCacheSize(size: Long) {
      calls += "set ambient size"
    }

    override fun setTileCountLimit(limit: Long) {
      calls += "set tile limit"
    }

    private fun pack(regionId: Long) =
      OfflinePack(
        OfflinePackOwner { _, _ -> calls += "set metadata" },
        regionId,
        definition,
        ByteArray(0),
      )
  }

  private companion object {
    val definition =
      OfflinePackDefinition.TilePyramid(
        styleUrl = "https://example.test/style.json",
        bounds = BoundingBox(west = -1.0, south = -1.0, east = 1.0, north = 1.0),
      )
  }
}
