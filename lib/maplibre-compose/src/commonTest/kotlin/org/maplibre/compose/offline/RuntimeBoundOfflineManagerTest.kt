package org.maplibre.compose.offline

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import org.maplibre.compose.map.RuntimeImplementation
import org.maplibre.spatialk.geojson.BoundingBox

class RuntimeBoundOfflineManagerTest {
  @Test
  fun unsupported_backend_rejects_every_operation() = runTest {
    val pack = RecordingOfflineManager().pack
    val runtime = runtime(UnsupportedOfflineManager)
    val manager = runtime.offlineManager

    assertEquals(emptySet(), manager.packs.value)
    assertFailsWith<UnsupportedOperationException> { manager.create(definition) }
    assertFailsWith<UnsupportedOperationException> { manager.resume(pack) }
    assertFailsWith<UnsupportedOperationException> { manager.pause(pack) }
    assertFailsWith<UnsupportedOperationException> { manager.delete(pack) }
    assertFailsWith<UnsupportedOperationException> { manager.invalidate(pack) }
    assertFailsWith<UnsupportedOperationException> { manager.mergeDatabase(databaseFile) }
    assertFailsWith<UnsupportedOperationException> { manager.invalidateAmbientCache() }
    assertFailsWith<UnsupportedOperationException> { manager.clearAmbientCache() }
    assertFailsWith<UnsupportedOperationException> { manager.setMaximumAmbientCacheSize(1) }
    runtime.close()
    runtime.awaitClosed()
  }

  @Test
  fun supported_backend_receives_every_operation() = runTest {
    val backend = RecordingOfflineManager()
    val runtime = runtime(backend)
    val manager = runtime.offlineManager

    assertSame(backend.pack, manager.packs.value.single())
    val metadata = byteArrayOf(1, 2)
    assertSame(backend.createdPack, manager.create(definition, metadata))
    assertEquals(definition, backend.createdDefinition)
    assertContentEquals(metadata, backend.createdMetadata)
    manager.resume(backend.pack)
    manager.pause(backend.pack)
    manager.delete(backend.pack)
    manager.invalidate(backend.pack)
    assertEquals(setOf(backend.mergedPack), manager.mergeDatabase(databaseFile))
    assertEquals(
      listOf("create", "resume", "pause", "delete", "invalidate", "merge"),
      backend.calls,
    )

    manager.invalidateAmbientCache()
    manager.clearAmbientCache()
    manager.setMaximumAmbientCacheSize(1)
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
  fun runtime_closure_rejects_every_offline_manager_operation() = runTest {
    val backend = RecordingOfflineManager()
    val releaseCleanup = CompletableDeferred<Unit>()
    val runtime =
      runtime(
        backend,
        closeResources = { releaseCleanup.await() },
      )
    val manager = runtime.offlineManager
    val retainedPack = manager.packs.value.single()
    val createdPack = manager.create(definition)
    backend.calls.clear()

    runtime.close()

    assertFailsWith<IllegalStateException> { manager.create(definition) }
    assertFailsWith<IllegalStateException> { manager.resume(backend.pack) }
    assertFailsWith<IllegalStateException> { manager.pause(backend.pack) }
    assertFailsWith<IllegalStateException> { manager.delete(backend.pack) }
    assertFailsWith<IllegalStateException> { manager.invalidate(backend.pack) }
    assertFailsWith<IllegalStateException> { manager.mergeDatabase(databaseFile) }
    assertFailsWith<IllegalStateException> { manager.invalidateAmbientCache() }
    assertFailsWith<IllegalStateException> { manager.clearAmbientCache() }
    assertFailsWith<IllegalStateException> { manager.setMaximumAmbientCacheSize(1) }
    assertFailsWith<IllegalStateException> { retainedPack.setMetadata(byteArrayOf(1)) }
    assertFailsWith<IllegalStateException> { createdPack.setMetadata(byteArrayOf(1)) }
    assertEquals(emptyList(), backend.calls)

    releaseCleanup.complete(Unit)
    runtime.awaitClosed()
  }

  private fun runtime(
    backend: OfflineManagerBackend,
    closeResources: suspend () -> Unit = {},
  ) =
    RuntimeImplementation(
      platformContext = null,
      closeResources = closeResources,
      logger = null,
      offlineManagerBackend = backend,
    )

  private class RecordingOfflineManager : OfflineManagerBackend, OfflinePackOwner {
    val calls = mutableListOf<String>()
    private var requireRuntimeOpen: () -> Unit = {}
    var createdDefinition: OfflinePackDefinition? = null
    var createdMetadata: ByteArray? = null
    val pack = pack(regionId = 1)
    val createdPack = pack(regionId = 2)
    val mergedPack = pack(regionId = 3)

    override val packs: StateFlow<Set<OfflinePack>> = MutableStateFlow(setOf(pack))

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
        assertEquals(RuntimeBoundOfflineManagerTest.databaseFile, databaseFile)
        calls += "merge"
      }

    override suspend fun invalidateAmbientCache() {
      calls += "invalidate ambient"
    }

    override suspend fun clearAmbientCache() {
      calls += "clear ambient"
    }

    override suspend fun setMaximumAmbientCacheSize(size: Long) {
      assertEquals(1L, size)
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
