package org.maplibre.compose.offline

import java.net.ServerSocket
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.maplibre.compose.desktop.DesktopRuntimeOptions
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.Polygon
import org.maplibre.spatialk.geojson.Position

/**
 * The offline pack lifecycle, against a real MapLibre database in a temporary directory.
 *
 * No map and no GPU: every call here goes to the offline manager's own runtime, which is the whole
 * reason offline management is usable before a map exists. Nothing leaves the machine either. A
 * download that is meant to succeed reads a style from a file, which the desktop resource provider
 * serves and which names no sources, so it is one resource and it finishes in milliseconds; a
 * download that is meant to fail points at a closed port on the loopback interface.
 */
class DesktopOfflinePackTest {

  private val directory = Files.createTempDirectory("maplibre-offline-pack-test")

  private val options =
    DesktopRuntimeOptions(cachePath = directory.resolve("cache.db"), maximumCacheSizeBytes = null)

  @AfterTest
  fun cleanUp() {
    // Before deleting the directory, so the database is closed rather than pulled out from under a
    // live runtime, and so each test leaves no thread behind for the next one.
    DesktopOfflineManager.disposeForTest(options)
    directory.toFile().deleteRecursively()
  }

  @Test
  fun `a created pack is listed with the definition and metadata it was created with`() =
    runBlocking {
      val manager = DesktopOfflineManager.forOptions(options)
      val definition = tilePyramid(writeStyle("listed.json"))
      val metadata = "listed by the pack lifecycle test".encodeToByteArray()

      val pack = withTimeout(OPERATION_TIMEOUT_MILLIS) { manager.create(definition, metadata) }

      // The pack is built from what MapLibre echoed back out of the region it stored, not from the
      // definition that was passed in, so this is a round trip through the database's own columns:
      // an unrepresentable definition comes back null and a lossy one comes back different.
      assertEquals(definition, pack.definition)
      assertContentEquals(metadata, pack.metadata)
      assertEquals(setOf(pack), manager.packs, "the created pack should be listed immediately")
    }

  @Test
  fun `updating metadata replaces what the pack reports`() = runBlocking {
    val manager = DesktopOfflineManager.forOptions(options)
    val pack =
      withTimeout(OPERATION_TIMEOUT_MILLIS) {
        manager.create(tilePyramid(writeStyle("metadata.json")), "before".encodeToByteArray())
      }

    val updated = "after, and longer than before".encodeToByteArray()
    withTimeout(OPERATION_TIMEOUT_MILLIS) { pack.setMetadata(updated) }

    assertContentEquals(updated, pack.metadata)

    // The manager copies the caller's array on the way in and copies native's echo on the way out,
    // so a caller that reuses its buffer cannot change what the pack reports afterwards.
    updated[0] = '!'.code.toByte()
    assertContentEquals("after, and longer than before".encodeToByteArray(), pack.metadata)
  }

  @Test
  fun `a deleted pack is no longer listed`() = runBlocking {
    val manager = DesktopOfflineManager.forOptions(options)
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

  /**
   * The claim offline support is built on: a pack is in the database, not in the process.
   *
   * Disposal is test-only — see [DesktopOfflineManager.disposeForTest] — because production keeps
   * one manager per options value for the life of the process. Without it this test would be handed
   * the same instance and its in-memory pack list back, and would pass without anything having been
   * read from disk.
   */
  @Test
  fun `a pack survives closing the manager and reopening the same database`() = runBlocking {
    val definition = tilePyramid(writeStyle("restart.json"))
    val metadata = "written before the restart".encodeToByteArray()

    val first = DesktopOfflineManager.forOptions(options)
    val created = withTimeout(OPERATION_TIMEOUT_MILLIS) { first.create(definition, metadata) }

    assertTrue(
      DesktopOfflineManager.disposeForTest(options),
      "the first manager's runtime thread should have stopped",
    )

    val second = DesktopOfflineManager.forOptions(options)
    assertNotSame(first, second, "disposing should make forOptions build a new manager")

    await("the reopened manager to list the pack it inherited") { second.packs.isNotEmpty() }

    val restored = second.packs.single()
    assertEquals(created.regionId, restored.regionId)
    assertEquals(definition, restored.definition)
    assertContentEquals(metadata, restored.metadata)
  }

  /**
   * The other definition MapLibre stores, and the one with more to lose in the database.
   *
   * A shape is written and read back through the geometry conversions in both directions, and "no
   * maximum zoom" is spelled as an infinity that an Int cannot hold, so it has to survive as a null
   * rather than as whatever `Double.POSITIVE_INFINITY.toInt()` produces.
   */
  @Test
  fun `a shape pack with no maximum zoom survives a reopen`() = runBlocking {
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
        minZoom = 2,
        maxZoom = null,
      )

    val first = DesktopOfflineManager.forOptions(options)
    withTimeout(OPERATION_TIMEOUT_MILLIS) { first.create(definition, ByteArray(0)) }
    assertTrue(DesktopOfflineManager.disposeForTest(options), "the first manager should stop")

    val second = DesktopOfflineManager.forOptions(options)
    await("the reopened manager to list the shape pack") { second.packs.isNotEmpty() }

    assertEquals(definition, second.packs.single().definition)
  }

  @Test
  fun `deleting a pack survives closing the manager and reopening the same database`() =
    runBlocking {
      val definition = tilePyramid(writeStyle("restart-delete.json"))
      val first = DesktopOfflineManager.forOptions(options)
      val kept =
        withTimeout(OPERATION_TIMEOUT_MILLIS) {
          first.create(definition, "kept".encodeToByteArray())
        }
      val removed =
        withTimeout(OPERATION_TIMEOUT_MILLIS) {
          first.create(definition, "removed".encodeToByteArray())
        }
      withTimeout(OPERATION_TIMEOUT_MILLIS) { first.delete(removed) }

      assertTrue(DesktopOfflineManager.disposeForTest(options), "the first manager should stop")

      val second = DesktopOfflineManager.forOptions(options)
      await("the reopened manager to list the pack that was kept") {
        second.packs.any { it.regionId == kept.regionId }
      }
      // The listing registers every region it found in one owner-thread callback, so a surviving
      // second region would land within instructions of the first rather than seconds later. This
      // waits far longer than that before concluding it is not coming.
      delay(SETTLE_MILLIS)

      assertEquals(listOf(kept.regionId), second.packs.map { it.regionId })
    }

  /**
   * The download state machine: paused, downloading and failing, paused again.
   *
   * The style is served from a loopback port with nothing listening on it, which keeps the download
   * running without a network and without a tile server. That matters more than it sounds: a style
   * that is merely *missing* is a permanent failure, so MapLibre finishes the download it cannot
   * make progress on and deactivates the region — measured, and the reason this test does not use a
   * `file:` URL for the failing case. A refused connection is retried instead, so the download
   * stays active and its error is what the pack reports between retries.
   */
  @Test
  fun `resuming a pack starts downloading and pausing reports it paused again`() = runBlocking {
    val manager = DesktopOfflineManager.forOptions(options)
    val pack =
      withTimeout(OPERATION_TIMEOUT_MILLIS) {
        manager.create(tilePyramid(unreachableStyleUrl()), ByteArray(0))
      }

    // A new pack is registered with an explicit status read, because a pack that has been told
    // nothing reads as Unknown, and a paused pack fetches nothing.
    val initial = awaitHealthy(pack, "the new pack's status") { true }
    assertEquals(DownloadStatus.Paused, initial.status)

    manager.resume(pack)

    // A paused pack issues no requests at all, so an error arriving is itself the evidence that
    // resuming reached MapLibre. The reason is asserted too, because a download that fails for a
    // reason the user could act on is the difference between "no signal" and "you are offline".
    await({
      "the resumed pack to report a failed fetch, but it reported ${pack.downloadProgress}"
    }) {
      pack.downloadProgress is DownloadProgress.Error
    }
    assertEquals("REASON_CONNECTION", (pack.downloadProgress as DownloadProgress.Error).reason)

    manager.pause(pack)

    // Pausing stops the retries, so this is the state the pack settles in rather than one it passes
    // through, and getting here at all means the error it was reporting was replaced.
    val paused =
      awaitHealthy(pack, "the paused pack to report itself paused") {
        it.status == DownloadStatus.Paused
      }
    assertEquals(DownloadStatus.Paused, paused.status)
  }

  /**
   * A finished download is worth nothing if the next run cannot tell that it finished.
   *
   * The status a reopened manager publishes comes from the database rather than from the download
   * that did the work, which is a different code path in MapLibre and the one every restart takes.
   * Measured because the inactive path could plausibly report a complete pack as merely paused — it
   * counts stored rows rather than a plan — and an offline screen that shows a finished pack as
   * paused invites the user to download it all over again.
   */
  @Test
  fun `a finished pack still reads as complete after a reopen`() = runBlocking {
    val first = DesktopOfflineManager.forOptions(options)
    val downloaded = downloadedPack(first, "finished.json")
    awaitHealthy(downloaded, "the pack to report itself complete") {
      it.status == DownloadStatus.Complete
    }

    assertTrue(DesktopOfflineManager.disposeForTest(options), "the first manager should stop")

    val second = DesktopOfflineManager.forOptions(options)
    await("the reopened manager to list the finished pack") { second.packs.isNotEmpty() }
    val restored = second.packs.single()

    val status = awaitHealthy(restored, "the restored pack's status") { true }
    assertEquals(DownloadStatus.Complete, status.status)
    assertTrue(status.completedResourceCount > 0, "the pack should still have its resources")
  }

  /**
   * The ambient cache and offline packs share a database and a resource table, and clearing one is
   * documented not to touch the other. That is worth measuring rather than trusting, because the
   * failure mode is a user losing a download they took a plane trip for.
   */
  @Test
  fun `clearing the ambient cache leaves a pack's downloaded resources in place`() = runBlocking {
    val manager = DesktopOfflineManager.forOptions(options)
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
   * Invalidation exists to make a pack revalidate against the server on its next download, so what
   * it must not do is throw the pack's resources away in the meantime — offline is exactly the
   * state in which they cannot be fetched again.
   */
  @Test
  fun `invalidating a pack keeps its downloaded resources`() = runBlocking {
    val manager = DesktopOfflineManager.forOptions(options)
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

  // ───────────────────────────── fixtures ─────────────────────────────

  /** Creates a pack over a local style and starts it; the caller waits for the part it needs. */
  private suspend fun downloadedPack(
    manager: DesktopOfflineManager,
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
   * Reads the pack's status back from the database rather than trusting what is already published.
   *
   * The published value is cleared first because the assertions that use this are about a count
   * *not* changing, which a stale read would satisfy just as well as a fresh one. Pausing is what
   * asks for the read: [DesktopOfflineManager.setDownloadState] always follows a state change with
   * an explicit status query.
   */
  private suspend fun rereadStatus(
    manager: DesktopOfflineManager,
    pack: OfflinePack,
  ): DownloadProgress.Healthy {
    pack.progressState.value = DownloadProgress.Unknown
    manager.pause(pack)
    return awaitHealthy(pack, "a fresh status read for pack ${pack.regionId}") { true }
  }

  private fun writeStyle(name: String): String {
    // No sources and no layers: MapLibre has exactly one resource to fetch, which makes "the
    // download finished" a fact this test can wait for rather than a race with a tile server.
    val file = directory.resolve(name)
    Files.writeString(file, """{"version":8,"name":"offline test","sources":{},"layers":[]}""")
    return file.toUri().toString()
  }

  /**
   * A style URL on a loopback port that was bound only long enough to be sure it is free, so
   * connecting to it is refused rather than answered or left hanging. Nothing leaves the machine.
   */
  private fun unreachableStyleUrl(): String {
    val port = ServerSocket(0).use { it.localPort }
    return "http://127.0.0.1:$port/style.json"
  }

  private fun tilePyramid(styleUrl: String): OfflinePackDefinition.TilePyramid =
    OfflinePackDefinition.TilePyramid(
      styleUrl = styleUrl,
      bounds = BoundingBox(southwest = Position(0.0, 0.0), northeast = Position(0.25, 0.25)),
      minZoom = 0,
      maxZoom = 1,
    )

  private suspend fun awaitHealthy(
    pack: OfflinePack,
    description: String,
    predicate: (DownloadProgress.Healthy) -> Boolean,
  ): DownloadProgress.Healthy {
    // The progress the pack was last seen reporting goes into the failure message, because "it
    // never got there" and "it got somewhere else" are different bugs and the timeout alone cannot
    // tell them apart.
    await({ "$description, but it last reported ${pack.downloadProgress}" }) {
      (pack.downloadProgress as? DownloadProgress.Healthy)?.let(predicate) == true
    }
    return pack.downloadProgress as DownloadProgress.Healthy
  }

  private suspend fun await(description: String, condition: () -> Boolean) =
    await({ description }, condition)

  /** Polls [condition] until it holds, failing rather than hanging if it never does. */
  private suspend fun await(describe: () -> String, condition: () -> Boolean) {
    val deadline = System.nanoTime() + OPERATION_TIMEOUT_MILLIS * 1_000_000
    while (!condition()) {
      if (System.nanoTime() > deadline) {
        fail("Timed out after ${OPERATION_TIMEOUT_MILLIS}ms waiting for ${describe()}")
      }
      delay(POLL_MILLIS)
    }
  }

  private companion object {
    /**
     * Generous, because every one of these operations is a database round trip on a machine that
     * may be running other tests; a real failure fails by assertion rather than by waiting this
     * out.
     */
    const val OPERATION_TIMEOUT_MILLIS = 30_000L

    const val POLL_MILLIS = 20L

    /** Long enough that "it never arrived" is a conclusion rather than a guess. */
    const val SETTLE_MILLIS = 2_000L
  }
}
