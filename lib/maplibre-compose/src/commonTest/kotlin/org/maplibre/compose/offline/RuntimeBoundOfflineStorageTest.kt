package org.maplibre.compose.offline

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import org.maplibre.compose.map.MapRuntime
import org.maplibre.compose.map.TestMainDispatcher
import org.maplibre.spatialk.geojson.BoundingBox

class RuntimeBoundOfflineStorageTest {
  @Test
  fun unsupported_backend_rejects_every_operation() = runTest {
    val pack = RecordingOfflineStorage().pack
    val runtime = runtime(UnsupportedOfflineStorage)
    val storage = runtime.offlineStorage

    assertEquals(emptySet(), (storage.state.value as OfflineStorageState.Ready).packs)
    assertFailsWith<UnsupportedOperationException> { storage.create(definition) }
    assertFailsWith<UnsupportedOperationException> { storage.resume(pack) }
    assertFailsWith<UnsupportedOperationException> { storage.pause(pack) }
    assertFailsWith<UnsupportedOperationException> { storage.delete(pack) }
    assertFailsWith<UnsupportedOperationException> { storage.invalidate(pack) }
    assertFailsWith<UnsupportedOperationException> { storage.mergeDatabase(databaseFile) }
    assertFailsWith<UnsupportedOperationException> { storage.invalidateAmbientCache() }
    assertFailsWith<UnsupportedOperationException> { storage.clearAmbientCache() }
    assertFailsWith<UnsupportedOperationException> { storage.setMaximumAmbientCacheSize(1) }
    runtime.close()
    runtime.awaitClosed()
  }

  @Test
  fun supported_backend_receives_every_operation() = runTest {
    val backend = RecordingOfflineStorage()
    val runtime = runtime(backend)
    val storage = runtime.offlineStorage

    assertSame(backend.pack, (storage.state.value as OfflineStorageState.Ready).packs.single())
    val metadata = byteArrayOf(1, 2)
    assertSame(backend.createdPack, storage.create(definition, metadata))
    assertEquals(definition, backend.createdDefinition)
    assertContentEquals(metadata, backend.createdMetadata)
    storage.resume(backend.pack)
    storage.pause(backend.pack)
    storage.delete(backend.pack)
    storage.invalidate(backend.pack)
    assertEquals(setOf(backend.mergedPack), storage.mergeDatabase(databaseFile))
    assertEquals(
      listOf("create", "resume", "pause", "delete", "invalidate", "merge"),
      backend.calls,
    )

    storage.invalidateAmbientCache()
    storage.clearAmbientCache()
    storage.setMaximumAmbientCacheSize(1)
    assertEquals(
      listOf(
        "create",
        "resume",
        "pause",
        "delete",
        "invalidate",
        "merge",
        "invalidate ambient",
        "clear ambient",
        "set ambient size",
      ),
      backend.calls,
    )
    runtime.close()
    runtime.awaitClosed()
  }

  @Test
  fun negative_ambient_cache_size_is_rejected_before_the_backend() = runTest {
    val backend = RecordingOfflineStorage()
    val runtime = runtime(backend)

    assertFailsWith<IllegalArgumentException> {
      runtime.offlineStorage.setMaximumAmbientCacheSize(-1)
    }
    assertEquals(emptyList(), backend.calls)
    runtime.close()
    runtime.awaitClosed()
  }

  @Test
  fun runtime_closure_rejects_every_offline_manager_operation() = runTest {
    val backend = RecordingOfflineStorage()
    val releaseCleanup = CompletableDeferred<Unit>()
    val runtime =
      runtime(
        backend,
        closeResources = { releaseCleanup.await() },
      )
    val storage = runtime.offlineStorage
    val retainedPack = (storage.state.value as OfflineStorageState.Ready).packs.single()
    val createdPack = storage.create(definition)
    backend.calls.clear()

    runtime.close()
    assertTrue(backend.closed, "Backend startup must be rejected before physical cleanup finishes")

    assertFailsWith<IllegalStateException> { storage.create(definition) }
    assertFailsWith<IllegalStateException> { storage.resume(backend.pack) }
    assertFailsWith<IllegalStateException> { storage.pause(backend.pack) }
    assertFailsWith<IllegalStateException> { storage.delete(backend.pack) }
    assertFailsWith<IllegalStateException> { storage.invalidate(backend.pack) }
    assertFailsWith<IllegalStateException> { storage.mergeDatabase(databaseFile) }
    assertFailsWith<IllegalStateException> { storage.invalidateAmbientCache() }
    assertFailsWith<IllegalStateException> { storage.clearAmbientCache() }
    assertFailsWith<IllegalStateException> { storage.setMaximumAmbientCacheSize(1) }
    assertFailsWith<IllegalStateException> { retainedPack.setMetadata(byteArrayOf(1)) }
    assertFailsWith<IllegalStateException> { createdPack.setMetadata(byteArrayOf(1)) }
    assertEquals(emptyList(), backend.calls)

    releaseCleanup.complete(Unit)
    runtime.awaitClosed()
  }

  private fun runtime(
    backend: OfflineStorageBackend,
    closeResources: suspend () -> Unit = {},
  ) =
    MapRuntime(
      platformContext = null,
      closeResources = closeResources,
      logger = null,
      offlineStorageBackend = backend,
      mainDispatcher = TestMainDispatcher(),
    )

  private class RecordingOfflineStorage : OfflineStorageBackend, OfflinePackOwner {
    var closed = false

    override fun close() {
      closed = true
    }

    val calls = mutableListOf<String>()
    private var requireRuntimeOpen: () -> Unit = {}
    var createdDefinition: OfflinePackDefinition? = null
    var createdMetadata: ByteArray? = null
    val pack = pack(regionId = 1)
    val createdPack = pack(regionId = 2)
    val mergedPack = pack(regionId = 3)

    override val state: StateFlow<OfflineStorageState> =
      MutableStateFlow(OfflineStorageState.Ready(setOf(pack)))

    override fun bindToRuntime(requireRuntimeOpen: () -> Unit) {
      this.requireRuntimeOpen = requireRuntimeOpen
    }

    override fun requireRuntimeOpen() = requireRuntimeOpen.invoke()

    override suspend fun updateMetadata(pack: OfflinePack, metadata: ByteArray) {
      calls += "set metadata"
    }

    override suspend fun create(
      definition: OfflinePackDefinition,
      metadata: ByteArray,
    ): OfflinePack = createdPack.also {
      calls += "create"
      createdDefinition = definition
      createdMetadata = metadata.copyOf()
    }

    override fun resume(pack: OfflinePack) {
      assertSame(this.pack, pack)
      calls += "resume"
    }

    override fun pause(pack: OfflinePack) {
      assertSame(this.pack, pack)
      calls += "pause"
    }

    override suspend fun delete(pack: OfflinePack) {
      assertSame(this.pack, pack)
      calls += "delete"
    }

    override suspend fun invalidate(pack: OfflinePack) {
      assertSame(this.pack, pack)
      calls += "invalidate"
    }

    override suspend fun mergeDatabase(databaseFile: Path): Set<OfflinePack> =
      setOf(mergedPack).also {
        assertEquals(RuntimeBoundOfflineStorageTest.databaseFile, databaseFile)
        calls += "merge"
      }

    override suspend fun invalidateAmbientCache() {
      calls += "invalidate ambient"
    }

    override suspend fun clearAmbientCache() {
      calls += "clear ambient"
    }

    override suspend fun setMaximumAmbientCacheSize(sizeBytes: Long) {
      assertEquals(1L, sizeBytes)
      calls += "set ambient size"
    }

    private fun pack(regionId: Long) = OfflinePack(this, regionId, definition, ByteArray(0))
  }

  private companion object {
    val definition =
      OfflinePackDefinition.TilePyramid(
        styleUrl = "https://example.test/style.json",
        bounds = BoundingBox(west = -1.0, south = -1.0, east = 1.0, north = 1.0),
        pixelRatio = 1f,
      )

    val databaseFile = Path("source-offline.db")
  }
}
