package org.maplibre.compose.offline

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.writeString
import org.maplibre.compose.mlnffi.FfiTestPlatform
import org.maplibre.compose.mlnffi.MlnFfiRuntime
import org.maplibre.compose.mlnffi.MlnFfiRuntimeOptions
import org.maplibre.compose.mlnffi.TestMapMain
import org.maplibre.compose.mlnffi.fileUrlOf
import org.maplibre.compose.mlnffi.unusedLoopbackPort
import org.maplibre.compose.resource.MapResourceError
import org.maplibre.compose.testing.MapTestResult
import org.maplibre.compose.testing.runMapTest
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.Polygon
import org.maplibre.spatialk.geojson.Position

/**
 * The offline pack lifecycle, against a real MapLibre database in a temporary directory. No map and
 * no GPU, and nothing leaves the machine: a download meant to succeed reads a source-less style
 * from a file, and one meant to fail points at a closed port on the loopback interface.
 */
class MlnFfiOfflinePackTest {

  private val cacheFile = FfiTestPlatform.createCacheFile()
  private val directory = requireNotNull(cacheFile.parent)

  private val options =
    MlnFfiRuntimeOptions(
      cacheFile = cacheFile,
      maximumCacheSizeBytes = null,
      mainDispatcher = TestMapMain.dispatcher,
    )
  private val owners = mutableMapOf<MlnFfiOfflineStorage, MlnFfiRuntime>()

  @Test
  fun a_created_pack_is_listed_with_the_definition_and_metadata_it_was_created_with() =
    offlineTest {
      val storage = storage()
      val definition = tilePyramid(writeStyle("listed.json"), pixelRatio = 2f)
      val metadata = "listed by the pack lifecycle test".encodeToByteArray()

      val pack = storage.create(definition, metadata)

      // The pack is built from what MapLibre echoed back out of the stored region, not from the
      // definition passed in, so this is a round trip through the database's own columns.
      assertEquals(definition, pack.definition)
      assertContentEquals(metadata, pack.metadata.value)
      assertEquals(
        setOf(pack),
        (storage.state.value as OfflineStorageState.Ready).packs,
        "the created pack should be listed immediately",
      )
    }

  @Test
  fun a_manager_rejects_a_pack_that_belongs_to_another_manager() = offlineTest {
    val first = storage()
    val pack = first.create(tilePyramid(writeStyle("foreign-pack.json")), ByteArray(0))
    val second = storage()

    assertFailsWith<IllegalArgumentException> { second.pause(pack) }
  }

  @Test
  fun updating_metadata_replaces_what_the_pack_reports() = offlineTest {
    val storage = storage()
    val pack =
      storage.create(tilePyramid(writeStyle("metadata.json")), "before".encodeToByteArray())

    val updated = "after, and longer than before".encodeToByteArray()
    pack.setMetadata(updated)

    assertContentEquals(updated, pack.metadata.value)

    // The storage copies in both directions, so a caller reusing its buffer cannot change what the
    // pack reports.
    updated[0] = '!'.code.toByte()
    assertContentEquals("after, and longer than before".encodeToByteArray(), pack.metadata.value)

    close(storage)
    val reopened = storage()
    assertContentEquals(
      "after, and longer than before".encodeToByteArray(),
      (reopened.state.value as OfflineStorageState.Ready).packs.single().metadata.value,
    )
  }

  @Test
  fun a_deleted_pack_is_no_longer_listed() = offlineTest {
    val storage = storage()
    val definition = tilePyramid(writeStyle("deleted.json"))
    val kept = storage.create(definition, "kept".encodeToByteArray())
    // Two packs, because deleting the only one cannot tell "removed the pack it was given" apart
    // from "cleared the list".
    val removed = storage.create(definition, "removed".encodeToByteArray())
    assertEquals(setOf(kept, removed), (storage.state.value as OfflineStorageState.Ready).packs)

    storage.delete(removed)

    assertEquals(setOf(kept), (storage.state.value as OfflineStorageState.Ready).packs)
  }

  /** A runtime can close its storage and a later runtime can reopen the same persistent cache. */
  @Test
  fun a_pack_survives_closing_the_manager_and_reopening_the_same_database() = offlineTest {
    val definition = tilePyramid(writeStyle("restart.json"))
    val metadata = "written before the restart".encodeToByteArray()

    val first = storage()
    val created = first.create(definition, metadata)

    close(first)

    val second = storage()
    assertNotSame(first, second)

    // The storage lists its stored packs before its constructor returns.
    val restored = (second.state.value as OfflineStorageState.Ready).packs.single()
    assertEquals(created.regionId, restored.regionId)
    assertEquals(definition, restored.definition)
    assertContentEquals(metadata, restored.metadata.value)
  }

  @Test
  fun fractional_zoom_bounds_survive_creation_and_a_database_reopen() = offlineTest {
    val styleUrl = writeStyle("fractional-zoom.json")
    val definitions =
      setOf(
        tilePyramid(styleUrl).copy(minZoom = 5.5, maxZoom = 12.75),
        OfflinePackDefinition.Shape(
          styleUrl = styleUrl,
          shape =
            Polygon(
              listOf(
                listOf(
                  Position(0.0, 0.0),
                  Position(0.25, 0.0),
                  Position(0.25, 0.25),
                  Position(0.0, 0.0),
                ),
              ),
            ),
          pixelRatio = 1f,
          minZoom = 5.5,
          maxZoom = 12.75,
        ),
      )
    val first = storage()
    for (definition in definitions) {
      val pack = first.create(definition, ByteArray(0))
      assertEquals(definition, pack.definition)
    }
    close(first)

    val reopened = storage()
    assertEquals(
      definitions,
      (reopened.state.value as OfflineStorageState.Ready).packs.map { it.definition }.toSet(),
    )
  }

  /** MapLibre stores "no maximum zoom" as infinity; the public definition represents it as null. */
  @Test
  fun a_shape_pack_with_no_maximum_zoom_survives_a_reopen() = offlineTest {
    val definition =
      OfflinePackDefinition.Shape(
        styleUrl = writeStyle("shape.json"),
        shape =
          Polygon(
            listOf(
              listOf(
                Position(0.0, 0.0),
                Position(0.25, 0.0),
                Position(0.25, 0.25),
                Position(0.0, 0.0),
              ),
            ),
          ),
        pixelRatio = 2f,
        minZoom = 2.0,
        maxZoom = null,
      )

    val first = storage()
    first.create(definition, ByteArray(0))
    close(first)

    val second = storage()
    assertEquals(
      definition,
      (second.state.value as OfflineStorageState.Ready).packs.single().definition,
    )
  }

  @Test
  fun deleting_a_pack_survives_closing_the_manager_and_reopening_the_same_database() = offlineTest {
    val definition = tilePyramid(writeStyle("restart-delete.json"))
    val first = storage()
    val kept = first.create(definition, "kept".encodeToByteArray())
    val removed = first.create(definition, "removed".encodeToByteArray())
    first.delete(removed)

    close(first)

    val second = storage()
    close(second)

    assertEquals(
      listOf(kept.regionId),
      (second.state.value as OfflineStorageState.Ready).packs.map { it.regionId },
    )
  }

  @Test
  fun merging_a_database_registers_its_packs_and_reuses_an_identical_pack() = offlineTest {
    val definition = tilePyramid(writeStyle("merge.json"))
    val sharedMetadata = "same pack".encodeToByteArray()
    val sourceFile = Path(directory, "merge-source.db")

    val source = storage(options.copy(cacheFile = sourceFile))
    source.create(definition, sharedMetadata)
    source.create(definition, "source-only pack".encodeToByteArray())
    close(source)

    val destination = storage()
    val existing = destination.create(definition, sharedMetadata)

    val merged = destination.mergeDatabase(sourceFile)

    assertEquals(2, merged.size)
    assertTrue(existing in merged, "an identical source pack should reuse the destination pack")
    assertEquals(merged, (destination.state.value as OfflineStorageState.Ready).packs)
    assertEquals(
      setOf("same pack", "source-only pack"),
      merged.map { requireNotNull(it.metadata.value).decodeToString() }.toSet(),
    )
  }

  /**
   * The style points at a closed loopback port rather than a missing `file:` URL: MapLibre treats a
   * missing style as a permanent failure and deactivates the region, while a refused connection is
   * retried, so the download stays active and reports its error between retries.
   */
  @Test
  fun resuming_a_pack_starts_downloading_and_pausing_reports_it_paused_again() = offlineTest {
    val storage = storage()
    val pack = storage.create(tilePyramid(unreachableStyleUrl()), ByteArray(0))

    // A pack that has been told nothing reads as NotReported, and a paused pack fetches nothing, so
    // registration issues an explicit status read.
    val initial = awaitHealthy(pack, "the new pack's status") { true }
    assertEquals(DownloadStatus.Paused, initial.status)

    storage.resume(pack)

    // A paused pack issues no requests at all, so an error arriving is itself the evidence that
    // resuming reached MapLibre.
    val failed =
      awaitProgress(pack, "the resumed pack to report a failed fetch") {
        it is DownloadProgress.Error
      }
    assertEquals(MapResourceError.Connection, (failed as DownloadProgress.Error).reason)

    storage.pause(pack)

    val paused =
      awaitHealthy(pack, "the paused pack to report itself paused") {
        it.status == DownloadStatus.Paused
      }
    assertEquals(DownloadStatus.Paused, paused.status)
  }

  /** Reopening must restore status from the database through the same code path used on restart. */
  @Test
  fun a_finished_pack_still_reads_as_complete_after_a_reopen() = offlineTest {
    val first = storage()
    val downloaded = downloadedPack(first, "finished.json")
    awaitHealthy(downloaded, "the pack to report itself complete") {
      it.status == DownloadStatus.Complete
    }

    close(first)

    val second = storage()
    val restored = (second.state.value as OfflineStorageState.Ready).packs.single()

    val status = awaitHealthy(restored, "the restored pack's status") { true }
    assertEquals(DownloadStatus.Complete, status.status)
    assertTrue(status.completedResourceCount > 0, "the pack should still have its resources")
  }

  // region fixtures

  /** Runs [block] on [TestMapMain], then closes the runtimes it left open and deletes the cache. */
  private fun offlineTest(block: suspend CoroutineScope.() -> Unit): MapTestResult = runMapTest {
    TestMapMain.deferCleanup {
      // Close the runtime before deleting its database.
      owners.keys.forEach { it.close() }
      owners.values.forEach { it.close() }
      owners.values.forEach { it.awaitClosed() }
      FfiTestPlatform.deleteCacheFile(cacheFile)
    }
    block()
  }

  private suspend fun close(storage: MlnFfiOfflineStorage) {
    storage.close()
    val owner = owners.getValue(storage)
    owner.close()
    owner.awaitClosed()
    owners.remove(storage)
  }

  private suspend fun storage(options: MlnFfiRuntimeOptions = this.options): MlnFfiOfflineStorage {
    val owner = MlnFfiRuntime(options)
    val storage = MlnFfiOfflineStorage(owner)
    owners[storage] = owner
    owner.start()
    storage.awaitReady()
    return storage
  }

  /** Creates a pack over a local style and starts it; the caller waits for the part it needs. */
  private suspend fun downloadedPack(
    storage: MlnFfiOfflineStorage,
    styleName: String,
  ): OfflinePack {
    val pack = storage.create(tilePyramid(writeStyle(styleName)), ByteArray(0))
    storage.resume(pack)
    return pack
  }

  private fun writeStyle(name: String): String {
    // No sources and no layers, so MapLibre has exactly one resource to fetch.
    val file = Path(directory, name)
    SystemFileSystem.sink(file).buffered().use {
      it.writeString("""{"version":8,"name":"offline test","sources":{},"layers":[]}""")
    }
    return fileUrlOf(file)
  }

  /**
   * A style URL on a loopback port bound only long enough to be sure it is free, so connecting to
   * it is refused rather than answered or left hanging.
   */
  private fun unreachableStyleUrl(): String = "http://127.0.0.1:${unusedLoopbackPort()}/style.json"

  private fun tilePyramid(
    styleUrl: String,
    pixelRatio: Float = 1f,
  ): OfflinePackDefinition.TilePyramid =
    OfflinePackDefinition.TilePyramid(
      styleUrl = styleUrl,
      bounds = BoundingBox(southwest = Position(0.0, 0.0), northeast = Position(0.25, 0.25)),
      pixelRatio = pixelRatio,
      minZoom = 0.0,
      maxZoom = 1.0,
    )

  private suspend fun awaitHealthy(
    pack: OfflinePack,
    description: String,
    predicate: (DownloadProgress.Healthy) -> Boolean,
  ): DownloadProgress.Healthy {
    val progress =
      awaitProgress(pack, description) { it is DownloadProgress.Healthy && predicate(it) }
    return progress as DownloadProgress.Healthy
  }

  /** Suspends until [pack] reports progress that satisfies [predicate], or fails. */
  private suspend fun awaitProgress(
    pack: OfflinePack,
    description: String,
    predicate: (DownloadProgress) -> Boolean,
  ): DownloadProgress =
    withTimeoutOrNull(OperationTimeout) { pack.downloadProgress.first { predicate(it) } }
      ?: fail(
        "Timed out after $OperationTimeout waiting for $description, but it last reported " +
          "${pack.downloadProgress.value}",
      )

  private companion object {
    /** Generous: progress comes from MapLibre's database and file source on a busy machine. */
    val OperationTimeout = 30.seconds
  }
  // endregion
}
