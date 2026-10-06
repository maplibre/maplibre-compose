package org.maplibre.compose.offline

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.writeString
import org.maplibre.compose.mlnffi.FfiTestPlatform
import org.maplibre.compose.mlnffi.MlnFfiRuntime
import org.maplibre.compose.mlnffi.MlnFfiRuntimeOptions
import org.maplibre.compose.mlnffi.fileUrlOf
import org.maplibre.compose.mlnffi.unusedLoopbackPort
import org.maplibre.compose.resource.MapResourceError
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

  private val options = MlnFfiRuntimeOptions(cacheFile = cacheFile, maximumCacheSizeBytes = null)
  private val owners = mutableMapOf<MlnFfiOfflineStorage, MlnFfiRuntime>()

  @AfterTest
  fun cleanUp() = runBlocking {
    // Close the runtime before deleting its database.
    owners.keys.forEach { it.close() }
    owners.values.forEach { it.close() }
    owners.values.forEach { it.awaitClosed() }
    FfiTestPlatform.deleteCacheFile(cacheFile)
  }

  @Test
  fun a_created_pack_is_listed_with_the_definition_and_metadata_it_was_created_with() =
    runBlocking {
      val storage = storage()
      val definition = tilePyramid(writeStyle("listed.json"), pixelRatio = 2f)
      val metadata = "listed by the pack lifecycle test".encodeToByteArray()

      val pack = withTimeout(OperationTimeoutMillis) { storage.create(definition, metadata) }

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
  fun a_manager_rejects_a_pack_that_belongs_to_another_manager() = runBlocking {
    val first = storage()
    val pack =
      withTimeout(OperationTimeoutMillis) {
        first.create(tilePyramid(writeStyle("foreign-pack.json")), ByteArray(0))
      }
    val second = storage()

    assertFailsWith<IllegalArgumentException> { second.pause(pack) }
    Unit
  }

  @Test
  fun updating_metadata_replaces_what_the_pack_reports() = runBlocking {
    val storage = storage()
    val pack =
      withTimeout(OperationTimeoutMillis) {
        storage.create(tilePyramid(writeStyle("metadata.json")), "before".encodeToByteArray())
      }

    val updated = "after, and longer than before".encodeToByteArray()
    withTimeout(OperationTimeoutMillis) { pack.setMetadata(updated) }

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
  fun a_deleted_pack_is_no_longer_listed() = runBlocking {
    val storage = storage()
    val definition = tilePyramid(writeStyle("deleted.json"))
    val kept =
      withTimeout(OperationTimeoutMillis) {
        storage.create(definition, "kept".encodeToByteArray())
      }
    // Two packs, because deleting the only one cannot tell "removed the pack it was given" apart
    // from "cleared the list".
    val removed =
      withTimeout(OperationTimeoutMillis) {
        storage.create(definition, "removed".encodeToByteArray())
      }
    assertEquals(setOf(kept, removed), (storage.state.value as OfflineStorageState.Ready).packs)

    withTimeout(OperationTimeoutMillis) { storage.delete(removed) }

    assertEquals(setOf(kept), (storage.state.value as OfflineStorageState.Ready).packs)
  }

  /** A runtime can close its storage and a later runtime can reopen the same persistent cache. */
  @Test
  fun a_pack_survives_closing_the_manager_and_reopening_the_same_database() = runBlocking {
    val definition = tilePyramid(writeStyle("restart.json"))
    val metadata = "written before the restart".encodeToByteArray()

    val first = storage()
    val created = withTimeout(OperationTimeoutMillis) { first.create(definition, metadata) }

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
  fun fractional_zoom_bounds_survive_creation_and_a_database_reopen() = runBlocking {
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
                )
              )
            ),
          pixelRatio = 1f,
          minZoom = 5.5,
          maxZoom = 12.75,
        ),
      )
    val first = storage()
    for (definition in definitions) {
      val pack = withTimeout(OperationTimeoutMillis) { first.create(definition, ByteArray(0)) }
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
  fun a_shape_pack_with_no_maximum_zoom_survives_a_reopen() = runBlocking {
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
              )
            )
          ),
        pixelRatio = 2f,
        minZoom = 2.0,
        maxZoom = null,
      )

    val first = storage()
    withTimeout(OperationTimeoutMillis) { first.create(definition, ByteArray(0)) }
    close(first)

    val second = storage()
    assertEquals(
      definition,
      (second.state.value as OfflineStorageState.Ready).packs.single().definition,
    )
  }

  @Test
  fun deleting_a_pack_survives_closing_the_manager_and_reopening_the_same_database() = runBlocking {
    val definition = tilePyramid(writeStyle("restart-delete.json"))
    val first = storage()
    val kept =
      withTimeout(OperationTimeoutMillis) { first.create(definition, "kept".encodeToByteArray()) }
    val removed =
      withTimeout(OperationTimeoutMillis) {
        first.create(definition, "removed".encodeToByteArray())
      }
    withTimeout(OperationTimeoutMillis) { first.delete(removed) }

    close(first)

    val second = storage()
    close(second)

    assertEquals(
      listOf(kept.regionId),
      (second.state.value as OfflineStorageState.Ready).packs.map { it.regionId },
    )
  }

  @Test
  fun merging_a_database_registers_its_packs_and_reuses_an_identical_pack() = runBlocking {
    val definition = tilePyramid(writeStyle("merge.json"))
    val sharedMetadata = "same pack".encodeToByteArray()
    val sourceFile = Path(directory, "merge-source.db")

    val source = storage(options.copy(cacheFile = sourceFile))
    withTimeout(OperationTimeoutMillis) { source.create(definition, sharedMetadata) }
    withTimeout(OperationTimeoutMillis) {
      source.create(definition, "source-only pack".encodeToByteArray())
    }
    close(source)

    val destination = storage()
    val existing =
      withTimeout(OperationTimeoutMillis) { destination.create(definition, sharedMetadata) }

    val merged = withTimeout(OperationTimeoutMillis) { destination.mergeDatabase(sourceFile) }

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
  fun resuming_a_pack_starts_downloading_and_pausing_reports_it_paused_again() = runBlocking {
    val storage = storage()
    val pack =
      withTimeout(OperationTimeoutMillis) {
        storage.create(tilePyramid(unreachableStyleUrl()), ByteArray(0))
      }

    // A pack that has been told nothing reads as Unknown, and a paused pack fetches nothing, so
    // registration issues an explicit status read.
    val initial = awaitHealthy(pack, "the new pack's status") { true }
    assertEquals(DownloadStatus.Paused, initial.status)

    storage.resume(pack)

    // A paused pack issues no requests at all, so an error arriving is itself the evidence that
    // resuming reached MapLibre.
    await({
      "the resumed pack to report a failed fetch, but it reported ${pack.downloadProgress.value}"
    }) {
      pack.downloadProgress.value is DownloadProgress.Error
    }
    assertEquals(
      MapResourceError.Connection,
      (pack.downloadProgress.value as DownloadProgress.Error).reason,
    )

    storage.pause(pack)

    val paused =
      awaitHealthy(pack, "the paused pack to report itself paused") {
        it.status == DownloadStatus.Paused
      }
    assertEquals(DownloadStatus.Paused, paused.status)
  }

  /** A background worker observes completion through plain flow collection. */
  @Test
  fun a_download_completes_for_a_collector_with_no_compose_host() = runBlocking {
    val storage = storage()
    val pack = downloadedPack(storage, "collected.json")

    val completed =
      withTimeout(OperationTimeoutMillis) {
        pack.downloadProgress.first {
          it is DownloadProgress.Healthy && it.status == DownloadStatus.Complete
        }
      }

    assertTrue((completed as DownloadProgress.Healthy).completedResourceCount > 0)
    val packs =
      withTimeout(OperationTimeoutMillis) {
        storage.state.first { it is OfflineStorageState.Ready && pack in it.packs }
      }
    assertEquals(setOf(pack), (packs as OfflineStorageState.Ready).packs)
  }

  /** Reopening must restore status from the database through the same code path used on restart. */
  @Test
  fun a_finished_pack_still_reads_as_complete_after_a_reopen() = runBlocking {
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

  private suspend fun close(storage: MlnFfiOfflineStorage) {
    storage.close()
    val owner = owners.getValue(storage)
    owner.close()
    owner.awaitClosed()
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
    val pack =
      withTimeout(OperationTimeoutMillis) {
        storage.create(tilePyramid(writeStyle(styleName)), ByteArray(0))
      }
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
    await({ "$description, but it last reported ${pack.downloadProgress.value}" }) {
      (pack.downloadProgress.value as? DownloadProgress.Healthy)?.let(predicate) == true
    }
    return pack.downloadProgress.value as DownloadProgress.Healthy
  }

  private suspend fun await(description: String, condition: () -> Boolean) =
    await({ description }, condition)

  /** Polls [condition] until it holds, failing rather than hanging if it never does. */
  private suspend fun await(describe: () -> String, condition: () -> Boolean) {
    val deadline = TimeSource.Monotonic.markNow() + OperationTimeoutMillis.milliseconds
    while (!condition()) {
      if (deadline.hasPassedNow()) {
        fail("Timed out after ${OperationTimeoutMillis}ms waiting for ${describe()}")
      }
      delay(PollMillis)
    }
  }

  private companion object {
    /** Generous: every one of these operations is a database round trip on a busy machine. */
    const val OperationTimeoutMillis = 30_000L

    const val PollMillis = 20L
  }
  // endregion
}
