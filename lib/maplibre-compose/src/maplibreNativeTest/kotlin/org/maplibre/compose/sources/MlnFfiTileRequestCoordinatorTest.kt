package org.maplibre.compose.sources

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.maplibre.compose.map.MapExtent
import org.maplibre.compose.map.MlnFfiMapRuntimeLoop
import org.maplibre.compose.mlnffi.FfiTestPlatform
import org.maplibre.compose.style.MlnFfiStyleBinding
import org.maplibre.compose.testing.RecordingList
import org.maplibre.nativeffi.geo.CanonicalTileId
import org.maplibre.nativeffi.map.MapHandle

class MlnFfiTileRequestCoordinatorTest {

  @Test
  fun different_tiles_load_concurrently() = withDroppingBinding { binding ->
    val started = RecordingList<TileCoordinate>()
    val release = CompletableDeferred<Unit>()
    val coordinator =
      coordinator(binding) {
        started += it
        release.await()
      }

    coordinator.fetch(CanonicalTileId(z = 1, x = 0, y = 0))
    coordinator.fetch(CanonicalTileId(z = 1, x = 1, y = 0))

    withTimeout(5.seconds) {
      while (started.size < 2) kotlinx.coroutines.yield()
    }
    assertEquals(setOf(TileCoordinate(1, 0, 0), TileCoordinate(1, 1, 0)), started.toSet())
    release.complete(Unit)
    coordinator.close()
  }

  @Test
  fun a_duplicate_request_cancels_and_replaces_the_older_job() = withDroppingBinding { binding ->
    val invocations = RecordingList<TileCoordinate>()
    val firstStarted = CompletableDeferred<Unit>()
    val firstCancelled = CompletableDeferred<Unit>()
    val secondFinished = CompletableDeferred<Unit>()
    val coordinator =
      coordinator(binding) { tile ->
        invocations += tile
        if (invocations.size == 1) {
          firstStarted.complete(Unit)
          try {
            awaitCancellation()
          } finally {
            firstCancelled.complete(Unit)
          }
        } else {
          secondFinished.complete(Unit)
        }
      }
    val tile = CanonicalTileId(z = 0, x = 0, y = 0)

    coordinator.fetch(tile)
    withTimeout(5.seconds) { firstStarted.await() }
    coordinator.fetch(tile)

    withTimeout(5.seconds) {
      firstCancelled.await()
      secondFinished.await()
    }
    assertEquals(2, invocations.size)
    coordinator.close()
  }

  @Test
  fun close_cancels_every_outstanding_job_and_ignores_later_fetches() =
    withDroppingBinding { binding ->
      val starts = RecordingList<TileCoordinate>()
      val firstStarted = CompletableDeferred<Unit>()
      val firstCancelled = CompletableDeferred<Unit>()
      val coordinator =
        coordinator(binding) { tile ->
          starts += tile
          firstStarted.complete(Unit)
          try {
            awaitCancellation()
          } finally {
            firstCancelled.complete(Unit)
          }
        }
      coordinator.fetch(CanonicalTileId(z = 0, x = 0, y = 0))
      withTimeout(5.seconds) { firstStarted.await() }

      coordinator.close()
      withTimeout(5.seconds) { firstCancelled.await() }
      coordinator.fetch(CanonicalTileId(z = 0, x = 0, y = 0))

      delay(100)
      assertEquals(1, starts.size, "a closed coordinator must not load")
    }

  @Test
  fun provider_failure_does_not_cancel_other_requests() = withDroppingBinding { binding ->
    val successful = CompletableDeferred<Unit>()
    val failureHandled = CompletableDeferred<Unit>()
    val coordinator =
      coordinator(binding) { tile ->
        if (tile.x == 0L) error("fixture failure") else successful.complete(Unit)
      }
    binding.onDrop = { failureHandled.complete(Unit) }

    coordinator.fetch(CanonicalTileId(z = 1, x = 0, y = 0))
    withTimeout(5.seconds) { failureHandled.await() }
    coordinator.fetch(CanonicalTileId(z = 1, x = 1, y = 0))

    withTimeout(5.seconds) { successful.await() }
    coordinator.close()
  }

  private fun coordinator(
    binding: MlnFfiStyleBinding,
    load: suspend (TileCoordinate) -> Unit,
  ): MlnFfiTileRequestCoordinator<Unit> =
    MlnFfiTileRequestCoordinator(
      name = "coordinator-test",
      binding = binding,
      load = load,
      deliver = { _, _, _ -> error("the dropping binding must not deliver") },
      fail = { _, _, error -> throw error },
    )

  private fun withDroppingBinding(action: suspend (DroppingBinding) -> Unit) = runBlocking {
    FfiTestPlatform.initialize()
    val cacheFile = FfiTestPlatform.createCacheFile()
    val binding = CompletableDeferred<DroppingBinding>()
    lateinit var loop: MlnFfiMapRuntimeLoop
    loop =
      MlnFfiMapRuntimeLoop(
        extent = MapExtent.fromLogical(1, 1, 1.0),
        cacheFile = cacheFile,
        getLogger = { null },
        onMapCreated = {},
        onMapPublished = { binding.complete(DroppingBinding(it, loop)) },
        onEvent = { _, _ -> },
        onEventsDrained = {},
        requestFrame = {},
        onFailure = { binding.completeExceptionally(it) },
      )
    try {
      loop.start()
      action(withTimeout(5.seconds) { binding.await() })
    } finally {
      loop.close()
      loop.awaitClosed()
      FfiTestPlatform.deleteCacheFile(cacheFile)
    }
  }

  private class DroppingBinding(map: MapHandle, loop: MlnFfiMapRuntimeLoop) :
    MlnFfiStyleBinding(map, loop, sessionOpen = { true }) {
    var onDrop: () -> Unit = {}

    override fun submit(onDropped: () -> Unit, action: (MapHandle) -> Unit) {
      onDropped()
      onDrop()
    }
  }
}
