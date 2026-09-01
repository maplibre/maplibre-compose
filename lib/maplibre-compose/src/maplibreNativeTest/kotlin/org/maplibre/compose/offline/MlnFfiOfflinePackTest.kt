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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.writeString
import org.maplibre.compose.mlnffi.FfiTestPlatform
import org.maplibre.compose.mlnffi.MlnFfiRuntimeOptions
import org.maplibre.compose.mlnffi.fileUrlOf
import org.maplibre.compose.mlnffi.unusedLoopbackPort
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
  private val managers = mutableListOf<MlnFfiOfflineManager>()

  @AfterTest
  fun cleanUp() {
    // Must precede the delete, so the database is closed rather than pulled out from under a live
    // runtime.
    managers.forEach { it.close() }
    FfiTestPlatform.deleteCacheFile(cacheFile)
  }

  @Test
  fun a_created_pack_is_listed_with_the_definition_and_metadata_it_was_created_with() =
    runBlocking {
      val manager = manager()
      val definition = tilePyramid(writeStyle("listed.json"), pixelRatio = 2f)
      val metadata = "listed by the pack lifecycle test".encodeToByteArray()

      val pack = withTimeout(OPERATION_TIMEOUT_MILLIS) { manager.create(definition, metadata) }

      // The pack is built from what MapLibre echoed back out of the stored region, not from the
      // definition passed in, so this is a round trip through the database's own columns.
      assertEquals(definition, pack.definition)
      assertContentEquals(metadata, pack.metadata)
      assertEquals(setOf(pack), manager.packs, "the created pack should be listed immediately")
    }

  @Test
  fun a_manager_rejects_a_pack_that_belongs_to_another_manager() = runBlocking {
    val first = manager()
    val pack =
      withTimeout(OPERATION_TIMEOUT_MILLIS) {
        first.create(tilePyramid(writeStyle("foreign-pack.json")), ByteArray(0))
      }
    val second = manager()

    assertFailsWith<IllegalArgumentException> { second.pause(pack) }
    Unit
  }

  @Test
  fun updating_metadata_replaces_what_the_pack_reports() = runBlocking {
    val manager = manager()
    val pack =
      withTimeout(OPERATION_TIMEOUT_MILLIS) {
        manager.create(tilePyramid(writeStyle("metadata.json")), "before".encodeToByteArray())
      }

    val updated = "after, and longer than before".encodeToByteArray()
    withTimeout(OPERATION_TIMEOUT_MILLIS) { pack.setMetadata(updated) }

    assertContentEquals(updated, pack.metadata)

    // The manager copies in both directions, so a caller reusing its buffer cannot change what the
    // pack reports.
    updated[0] = '!'.code.toByte()
    assertContentEquals("after, and longer than before".encodeToByteArray(), pack.metadata)
  }

  @Test
  fun a_deleted_pack_is_no_longer_listed() = runBlocking {
    val manager = manager()
    val definition = tilePyramid(writeStyle("deleted.json"))
    val kept =
      withTimeout(OPERATION_TIMEOUT_MILLIS) {
        manager.create(definition, "kept".encodeToByteArray())
      }
    // Two packs, because deleting the only one cannot tell "removed the pack it was given" apart
    // from "cleared the list".
    val removed =
      withTimeout(OPERATION_TIMEOUT_MILLIS) {
        manager.create(definition, "removed".encodeToByteArray())
      }
    assertEquals(setOf(kept, removed), manager.packs)

    withTimeout(OPERATION_TIMEOUT_MILLIS) { manager.delete(removed) }

    assertEquals(setOf(kept), manager.packs)
  }

  /** A runtime can close its manager and a later runtime can reopen the same persistent cache. */
  @Test
  fun a_pack_survives_closing_the_manager_and_reopening_the_same_database() = runBlocking {
    val definition = tilePyramid(writeStyle("restart.json"))
    val metadata = "written before the restart".encodeToByteArray()

    val first = manager()
    val created = withTimeout(OPERATION_TIMEOUT_MILLIS) { first.create(definition, metadata) }

    assertTrue(first.close(), "the first manager's runtime thread should have stopped")

    val second = manager()
    assertNotSame(first, second)

    await("the reopened manager to list the pack it inherited") { second.packs.isNotEmpty() }

    val restored = second.packs.single()
    assertEquals(created.regionId, restored.regionId)
    assertEquals(definition, restored.definition)
    assertContentEquals(metadata, restored.metadata)
  }

  /**
   * MapLibre spells "no maximum zoom" as an infinity an Int cannot hold, so it has to survive as a
   * null rather than as whatever `Double.POSITIVE_INFINITY.toInt()` produces.
   */
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
        minZoom = 2,
        maxZoom = null,
      )

    val first = manager()
    withTimeout(OPERATION_TIMEOUT_MILLIS) { first.create(definition, ByteArray(0)) }
    assertTrue(first.close(), "the first manager should stop")

    val second = manager()
    await("the reopened manager to list the shape pack") { second.packs.isNotEmpty() }

    assertEquals(definition, second.packs.single().definition)
  }

  @Test
  fun deleting_a_pack_survives_closing_the_manager_and_reopening_the_same_database() = runBlocking {
    val definition = tilePyramid(writeStyle("restart-delete.json"))
    val first = manager()
    val kept =
      withTimeout(OPERATION_TIMEOUT_MILLIS) { first.create(definition, "kept".encodeToByteArray()) }
    val removed =
      withTimeout(OPERATION_TIMEOUT_MILLIS) {
        first.create(definition, "removed".encodeToByteArray())
      }
    withTimeout(OPERATION_TIMEOUT_MILLIS) { first.delete(removed) }

    assertTrue(first.close(), "the first manager should stop")

    val second = manager()
    await("the reopened manager to list the pack that was kept") {
      second.packs.any { it.regionId == kept.regionId }
    }
    // The listing registers every region it found in one owner-thread callback, so a surviving
    // second region would land right after the first.
    delay(SETTLE_MILLIS)

    assertEquals(listOf(kept.regionId), second.packs.map { it.regionId })
  }

  /**
   * The style points at a closed loopback port rather than a missing `file:` URL: MapLibre treats a
   * missing style as a permanent failure and deactivates the region, while a refused connection is
   * retried, so the download stays active and reports its error between retries.
   */
  @Test
  fun resuming_a_pack_starts_downloading_and_pausing_reports_it_paused_again() = runBlocking {
    val manager = manager()
    val pack =
      withTimeout(OPERATION_TIMEOUT_MILLIS) {
        manager.create(tilePyramid(unreachableStyleUrl()), ByteArray(0))
      }

    // A pack that has been told nothing reads as Unknown, and a paused pack fetches nothing, so
    // registration issues an explicit status read.
    val initial = awaitHealthy(pack, "the new pack's status") { true }
    assertEquals(DownloadStatus.Paused, initial.status)

    manager.resume(pack)

    // A paused pack issues no requests at all, so an error arriving is itself the evidence that
    // resuming reached MapLibre.
    await({
      "the resumed pack to report a failed fetch, but it reported ${pack.downloadProgress}"
    }) {
      pack.downloadProgress is DownloadProgress.Error
    }
    assertEquals("REASON_CONNECTION", (pack.downloadProgress as DownloadProgress.Error).reason)

    manager.pause(pack)

    val paused =
      awaitHealthy(pack, "the paused pack to report itself paused") {
        it.status == DownloadStatus.Paused
      }
    assertEquals(DownloadStatus.Paused, paused.status)
  }

  /**
   * The status a reopened manager publishes comes from the database rather than from the download
   * that did the work — a different MapLibre code path, and the one every restart takes.
   */
  @Test
  fun a_finished_pack_still_reads_as_complete_after_a_reopen() = runBlocking {
    val first = manager()
    val downloaded = downloadedPack(first, "finished.json")
    awaitHealthy(downloaded, "the pack to report itself complete") {
      it.status == DownloadStatus.Complete
    }

    assertTrue(first.close(), "the first manager should stop")

    val second = manager()
    await("the reopened manager to list the finished pack") { second.packs.isNotEmpty() }
    val restored = second.packs.single()

    val status = awaitHealthy(restored, "the restored pack's status") { true }
    assertEquals(DownloadStatus.Complete, status.status)
    assertTrue(status.completedResourceCount > 0, "the pack should still have its resources")
  }

  /**
   * The ambient cache and offline packs share a database and a resource table, and clearing one is
   * documented not to touch the other.
   */
  @Test
  fun clearing_the_ambient_cache_leaves_a_pack_s_downloaded_resources_in_place() = runBlocking {
    val manager = manager()
    val pack = downloadedPack(manager, "ambient-clear.json")
    val downloaded =
      awaitHealthy(pack, "the pack to finish downloading") { it.completedResourceCount > 0 }

    withTimeout(OPERATION_TIMEOUT_MILLIS) { manager.clearAmbientCache() }

    val after = rereadStatus(manager, pack)
    assertEquals(
      downloaded.completedResourceCount,
      after.completedResourceCount,
      "clearing the ambient cache discarded a resource the offline pack owns",
    )
    assertEquals(downloaded.completedResourceBytes, after.completedResourceBytes)
  }

  /**
   * Invalidation marks a pack for revalidation on its next download; it must not discard the
   * resources in the meantime.
   */
  @Test
  fun invalidating_a_pack_keeps_its_downloaded_resources() = runBlocking {
    val manager = manager()
    val pack = downloadedPack(manager, "invalidate.json")
    val downloaded =
      awaitHealthy(pack, "the pack to finish downloading") { it.completedResourceCount > 0 }

    withTimeout(OPERATION_TIMEOUT_MILLIS) { manager.invalidate(pack) }

    val after = rereadStatus(manager, pack)
    assertEquals(
      downloaded.completedResourceCount,
      after.completedResourceCount,
      "invalidating the pack discarded resources instead of marking them stale",
    )
    assertEquals(setOf(pack), manager.packs, "an invalidated pack should still be listed")
  }

  // region fixtures

  private fun manager(): MlnFfiOfflineManager =
    MlnFfiOfflineManager(options).also { managers += it }

  /** Creates a pack over a local style and starts it; the caller waits for the part it needs. */
  private suspend fun downloadedPack(
    manager: MlnFfiOfflineManager,
    styleName: String,
  ): OfflinePack {
    val pack =
      withTimeout(OPERATION_TIMEOUT_MILLIS) {
        manager.create(tilePyramid(writeStyle(styleName)), ByteArray(0))
      }
    manager.resume(pack)
    return pack
  }

  /**
   * Reads the pack's status back from the database, clearing the published value first so a stale
   * read cannot satisfy an assertion about a count *not* changing. Pausing is what asks for the
   * read: [MlnFfiOfflineManager.setDownloadState] follows a state change with a status query.
   */
  private suspend fun rereadStatus(
    manager: MlnFfiOfflineManager,
    pack: OfflinePack,
  ): DownloadProgress.Healthy {
    pack.progressState.value = DownloadProgress.Unknown
    manager.pause(pack)
    return awaitHealthy(pack, "a fresh status read for pack ${pack.regionId}") { true }
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
      minZoom = 0,
      maxZoom = 1,
    )

  private suspend fun awaitHealthy(
    pack: OfflinePack,
    description: String,
    predicate: (DownloadProgress.Healthy) -> Boolean,
  ): DownloadProgress.Healthy {
    await({ "$description, but it last reported ${pack.downloadProgress}" }) {
      (pack.downloadProgress as? DownloadProgress.Healthy)?.let(predicate) == true
    }
    return pack.downloadProgress as DownloadProgress.Healthy
  }

  private suspend fun await(description: String, condition: () -> Boolean) =
    await({ description }, condition)

  /** Polls [condition] until it holds, failing rather than hanging if it never does. */
  private suspend fun await(describe: () -> String, condition: () -> Boolean) {
    val deadline = TimeSource.Monotonic.markNow() + OPERATION_TIMEOUT_MILLIS.milliseconds
    while (!condition()) {
      if (deadline.hasPassedNow()) {
        fail("Timed out after ${OPERATION_TIMEOUT_MILLIS}ms waiting for ${describe()}")
      }
      delay(POLL_MILLIS)
    }
  }

  private companion object {
    /** Generous: every one of these operations is a database round trip on a busy machine. */
    const val OPERATION_TIMEOUT_MILLIS = 30_000L

    const val POLL_MILLIS = 20L

    /** Long enough that "it never arrived" is a conclusion rather than a guess. */
    const val SETTLE_MILLIS = 2_000L
  }
  // endregion
}
