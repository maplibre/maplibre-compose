package org.maplibre.compose.sources

import co.touchlab.kermit.Logger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.maplibre.compose.style.MlnFfiStyleBinding
import org.maplibre.compose.testing.RecordingList
import org.maplibre.nativeffi.geo.CanonicalTileId
import org.maplibre.nativeffi.map.MapHandle
import org.maplibre.nativeffi.render.RenderSessionHandle

class MlnFfiTileRequestCoordinatorTest {

  @Test
  fun different_tiles_load_concurrently() = runBlocking {
    val started = RecordingList<TileCoordinate>()
    val release = CompletableDeferred<Unit>()
    val coordinator = coordinator {
      started += it
      release.await()
    }
    coordinator.attach(DroppingBinding())

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
  fun a_duplicate_request_cancels_and_replaces_the_older_job() = runBlocking {
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
    coordinator.attach(DroppingBinding())
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
  fun detach_cancels_every_outstanding_job_and_reattach_accepts_new_work() = runBlocking {
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
    coordinator.attach(DroppingBinding())
    coordinator.fetch(CanonicalTileId(z = 0, x = 0, y = 0))
    withTimeout(5.seconds) { firstStarted.await() }

    coordinator.detach()
    withTimeout(5.seconds) { firstCancelled.await() }
    coordinator.attach(DroppingBinding())
    coordinator.fetch(CanonicalTileId(z = 0, x = 0, y = 0))

    withTimeout(5.seconds) { secondStarted.await() }
    assertTrue(starts.size == 2)
    coordinator.detach()
  }

  @Test
  fun provider_failure_does_not_cancel_other_requests() = runBlocking {
    val successful = CompletableDeferred<Unit>()
    val coordinator = coordinator { tile ->
      if (tile.x == 0L) error("fixture failure") else successful.complete(Unit)
    }
    coordinator.attach(DroppingBinding())

    coordinator.fetch(CanonicalTileId(z = 1, x = 0, y = 0))
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

  private class DroppingBinding : MlnFfiStyleBinding {
    override val featureStateStore: MlnFfiFeatureStateStore? = null
    override val isLoaded = true
    override val logger: Logger? = null

    override fun onUnload(action: () -> Unit): () -> Unit = {}

    override fun <T> readMap(action: (MapHandle) -> T): T? = null

    override fun <T> mutateMap(abandon: () -> Unit, action: (MapHandle) -> T): T? {
      abandon()
      return null
    }

    override fun <T> withRenderSession(action: (RenderSessionHandle) -> T): T? = null
  }
}
