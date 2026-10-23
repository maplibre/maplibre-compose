@file:OptIn(ExperimentalAtomicApi::class)

package org.maplibre.compose.sources

import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.maplibre.compose.mlnffi.FfiTestPlatform
import org.maplibre.compose.mlnffi.MlnFfiRuntime
import org.maplibre.compose.mlnffi.MlnFfiRuntimeOptions
import org.maplibre.compose.mlnffi.TestLatch
import org.maplibre.compose.mlnffi.TestMapMain
import org.maplibre.compose.style.LoadedStyleContents
import org.maplibre.compose.style.MlnFfiStyleBinding
import org.maplibre.compose.testing.RecordingList
import org.maplibre.compose.testing.runMapTest
import org.maplibre.nativeffi.generated.CanonicalTileId
import org.maplibre.nativeffi.generated.GeneratedApi
import org.maplibre.nativeffi.generated.MapHandle

class MlnFfiTileRequestCoordinatorTest {

  @Test
  fun different_tiles_load_concurrently() = withDroppingBinding { binding ->
    val started = RecordingList<TileCoordinate>()
    val bothStarted = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    val coordinator =
      coordinator(binding) {
        started += it
        if (started.size == 2) bothStarted.complete(Unit)
        release.await()
      }

    coordinator.fetch(CanonicalTileId(z = 1u, x = 0u, y = 0u))
    coordinator.fetch(CanonicalTileId(z = 1u, x = 1u, y = 0u))

    withTimeout(5.seconds) { bothStarted.await() }
    assertEquals(setOf(TileCoordinate(1, 0, 0), TileCoordinate(1, 1, 0)), started.toSet())
    release.complete(Unit)
    coordinator.close()
  }

  @Test
  fun blocking_loads_leave_default_dispatcher_work_running() = withDroppingBinding { binding ->
    // More than a pool sized to the CPU count, such as Dispatchers.Default, runs at once.
    val loads = 32
    val started = AtomicInt(0)
    val allStarted = CompletableDeferred<Unit>()
    val release = TestLatch(1)
    val coordinator =
      coordinator(binding) {
        if (started.incrementAndFetch() == loads) allStarted.complete(Unit)
        // Blocks its thread, as a provider's blocking read would.
        release.await(10_000)
      }
    try {
      repeat(loads) { coordinator.fetch(CanonicalTileId(z = 6u, x = it.toUInt(), y = 0u)) }
      withTimeout(5.seconds) { allStarted.await() }
      val defaultWork = CompletableDeferred<Unit>()
      CoroutineScope(Dispatchers.Default).launch { defaultWork.complete(Unit) }
      withTimeout(5.seconds) { defaultWork.await() }
    } finally {
      release.countDown()
      coordinator.close()
    }
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
    val answers = RecordingList<Unit>()
    val answered = CompletableDeferred<Unit>()
    binding.onDrop = {
      answers += Unit
      answered.complete(Unit)
    }
    val tile = CanonicalTileId(z = 0u, x = 0u, y = 0u)

    coordinator.fetch(tile)
    withTimeout(5.seconds) { firstStarted.await() }
    coordinator.fetch(tile)

    withTimeout(5.seconds) {
      firstCancelled.await()
      secondFinished.await()
      answered.await()
    }
    assertEquals(2, invocations.size)
    assertEquals(1, answers.size, "the replaced request must not be answered")
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
      val answers = RecordingList<Unit>()
      binding.onDrop = { answers += Unit }
      coordinator.fetch(CanonicalTileId(z = 0u, x = 0u, y = 0u))
      withTimeout(5.seconds) { firstStarted.await() }

      coordinator.close()
      withTimeout(5.seconds) { firstCancelled.await() }
      coordinator.fetch(CanonicalTileId(z = 0u, x = 0u, y = 0u))

      assertEquals(1, starts.size, "a closed coordinator must not load")
      assertEquals(0, answers.size, "a closed coordinator must not answer")
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

    coordinator.fetch(CanonicalTileId(z = 1u, x = 0u, y = 0u))
    withTimeout(5.seconds) { failureHandled.await() }
    coordinator.fetch(CanonicalTileId(z = 1u, x = 1u, y = 0u))

    withTimeout(5.seconds) { successful.await() }
    coordinator.close()
  }

  @Test
  fun a_provider_timeout_answers_the_tile() = withDroppingBinding { binding ->
    val answered = CompletableDeferred<Unit>()
    binding.onDrop = { answered.complete(Unit) }
    val coordinator = coordinator(binding) { withTimeout(1.milliseconds) { awaitCancellation() } }

    coordinator.fetch(CanonicalTileId(z = 0u, x = 0u, y = 0u))

    withTimeout(5.seconds) { answered.await() }
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

  private fun withDroppingBinding(action: suspend (DroppingBinding) -> Unit) = runMapTest {
    FfiTestPlatform.initialize()
    val cacheFile = FfiTestPlatform.createCacheFile()
    val owner =
      MlnFfiRuntime(MlnFfiRuntimeOptions(cacheFile, mainDispatcher = TestMapMain.dispatcher)).also {
        it.start()
      }
    try {
      val map =
        withTimeout(5.seconds) {
          owner.awaitRuntime().createMap(GeneratedApi.mapOptionsDefault()).await()
        }
      try {
        action(DroppingBinding(map, owner))
      } finally {
        map.release().await()
      }
    } finally {
      owner.close()
      owner.awaitClosed()
      FfiTestPlatform.deleteCacheFile(cacheFile)
    }
  }

  private class DroppingBinding(map: MapHandle, owner: MlnFfiRuntime) :
    MlnFfiStyleBinding(
      map,
      owner,
      LoadedStyleContents(emptyList(), emptyList(), null),
      sessionOpen = { true },
    ) {
    var onDrop: () -> Unit = {}

    override fun submit(onDropped: () -> Unit, command: (MapHandle) -> Unit) {
      onDropped()
      onDrop()
    }
  }
}
