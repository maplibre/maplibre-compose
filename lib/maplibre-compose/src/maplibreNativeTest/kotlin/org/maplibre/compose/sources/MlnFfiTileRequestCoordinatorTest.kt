package org.maplibre.compose.sources

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.maplibre.compose.map.MapExtent
import org.maplibre.compose.map.MlnFfiMapRuntimeLoop
import org.maplibre.compose.mlnffi.FfiTestPlatform
import org.maplibre.compose.style.MlnFfiStyleBinding
import org.maplibre.compose.testing.RecordingList
import org.maplibre.nativeffi.geo.CanonicalTileId
import org.maplibre.nativeffi.map.MapHandle
import org.maplibre.nativeffi.render.RenderSessionHandle

class MlnFfiTileRequestCoordinatorTest {

  @Test
  fun different_tiles_load_concurrently() = withDroppingBinding { binding ->
    val started = RecordingList<TileCoordinate>()
    val release = CompletableDeferred<Unit>()
    val coordinator = coordinator {
      started += it
      release.await()
    }
    coordinator.attach(binding)

    coordinator.fetch(CanonicalTileId(z = 1, x = 0, y = 0))
    coordinator.fetch(CanonicalTileId(z = 1, x = 1, y = 0))

    withTimeout(5.seconds) {
      while (started.size < 2) kotlinx.coroutines.yield()
    }
    assertEquals(setOf(TileCoordinate(1, 0, 0), TileCoordinate(1, 1, 0)), started.toSet())
    release.complete(Unit)
    coordinator.detach()
  }

  @Test
  fun a_duplicate_request_cancels_and_replaces_the_older_job() = withDroppingBinding { binding ->
    val invocations = RecordingList<TileCoordinate>()
    val firstStarted = CompletableDeferred<Unit>()
    val firstCancelled = CompletableDeferred<Unit>()
    val secondFinished = CompletableDeferred<Unit>()
    val coordinator = coordinator { tile ->
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
    coordinator.attach(binding)
    val tile = CanonicalTileId(z = 0, x = 0, y = 0)

    coordinator.fetch(tile)
    withTimeout(5.seconds) { firstStarted.await() }
    coordinator.fetch(tile)

    withTimeout(5.seconds) {
      firstCancelled.await()
      secondFinished.await()
    }
    assertEquals(2, invocations.size)
    coordinator.detach()
  }

  @Test
  fun detach_cancels_every_outstanding_job_and_reattach_accepts_new_work() =
    withDroppingBinding { binding ->
      val starts = RecordingList<TileCoordinate>()
      val firstStarted = CompletableDeferred<Unit>()
      val firstCancelled = CompletableDeferred<Unit>()
      val secondStarted = CompletableDeferred<Unit>()
      val coordinator = coordinator { tile ->
        starts += tile
        if (starts.size == 1) {
          firstStarted.complete(Unit)
          try {
            awaitCancellation()
          } finally {
            firstCancelled.complete(Unit)
          }
        } else {
          secondStarted.complete(Unit)
        }
      }
      coordinator.attach(binding)
      coordinator.fetch(CanonicalTileId(z = 0, x = 0, y = 0))
      withTimeout(5.seconds) { firstStarted.await() }

      coordinator.detach()
      withTimeout(5.seconds) { firstCancelled.await() }
      coordinator.attach(binding)
      coordinator.fetch(CanonicalTileId(z = 0, x = 0, y = 0))

      withTimeout(5.seconds) { secondStarted.await() }
      assertTrue(starts.size == 2)
      coordinator.detach()
    }

  @Test
  fun provider_failure_does_not_cancel_other_requests() = withDroppingBinding { binding ->
    val successful = CompletableDeferred<Unit>()
    val failureHandled = CompletableDeferred<Unit>()
    val coordinator = coordinator { tile ->
      if (tile.x == 0L) error("fixture failure") else successful.complete(Unit)
    }
    binding.onDrop = { failureHandled.complete(Unit) }
    coordinator.attach(binding)

    coordinator.fetch(CanonicalTileId(z = 1, x = 0, y = 0))
    withTimeout(5.seconds) { failureHandled.await() }
    coordinator.fetch(CanonicalTileId(z = 1, x = 1, y = 0))

    withTimeout(5.seconds) { successful.await() }
    coordinator.detach()
  }

  private fun coordinator(
    load: suspend (TileCoordinate) -> Unit
  ): MlnFfiTileRequestCoordinator<Unit> =
    MlnFfiTileRequestCoordinator(
      name = "coordinator-test",
      load = load,
      deliver = { _, _, _ -> error("the dropping binding must not deliver") },
      fail = { _, _, error -> throw error },
    )

  private fun withDroppingBinding(action: suspend (DroppingBinding) -> Unit) = runBlocking {
    FfiTestPlatform.initialize()
    val cacheFile = FfiTestPlatform.createCacheFile()
    val binding = CompletableDeferred<DroppingBinding>()
    val loop =
      MlnFfiMapRuntimeLoop(
        extent = MapExtent.fromLogical(1, 1, 1.0),
        cacheFile = cacheFile,
        getLogger = { null },
        onMapCreated = {},
        onMapPublished = { binding.complete(DroppingBinding(it)) },
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

  private class DroppingBinding(map: MapHandle) : MlnFfiStyleBinding(map, sessionOpen = { true }) {
    var onDrop: () -> Unit = {}

    override fun postOrAbandon(abandon: () -> Unit, action: (MapHandle) -> Unit) {
      abandon()
      onDrop()
    }

    override suspend fun <T> awaitRenderSession(action: (RenderSessionHandle) -> T): T? = null
  }
}
