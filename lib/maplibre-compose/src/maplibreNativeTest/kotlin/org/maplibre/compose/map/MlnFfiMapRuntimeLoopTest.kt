@file:OptIn(ExperimentalAtomicApi::class)

package org.maplibre.compose.map

import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.maplibre.compose.logging.MapLog
import org.maplibre.compose.mlnffi.FfiTestPlatform
import org.maplibre.compose.mlnffi.MlnFfiRuntime
import org.maplibre.compose.mlnffi.MlnFfiRuntimeOptions
import org.maplibre.compose.mlnffi.TestLatch
import org.maplibre.compose.mlnffi.launchTestTask
import org.maplibre.compose.testing.MapTestResult
import org.maplibre.compose.testing.RecordingList
import org.maplibre.compose.testing.runMapTest
import org.maplibre.nativeffi.map.MapHandle
import org.maplibre.nativeffi.runtime.RuntimeEventType

class MlnFfiMapRuntimeLoopTest {

  @Test
  fun a_failed_loop_finalizes_the_published_map_on_its_owner_thread() = runBlocking {
    FfiTestPlatform.initialize()
    val cacheFile = FfiTestPlatform.createCacheFile()
    val owner = MlnFfiRuntime(MlnFfiRuntimeOptions(cacheFile)).also { it.start() }
    val failed = TestLatch(1)
    val finalized = TestLatch(1)
    val releaseFinalizer = TestLatch(1)
    val finalizerReleased = CompletableDeferred<Boolean>()
    val finalizerWasOnOwner = AtomicBoolean(false)
    val finalizerSawPublishedMap = AtomicBoolean(false)
    val expectedFailure = IllegalStateException("stop after publication")
    lateinit var loop: MlnFfiMapRuntimeLoop
    loop =
      MlnFfiMapRuntimeLoop(
        extent = MapExtent.fromLogical(1, 1, 1.0),
        owner = owner,
        getLogger = { MapLog },
        onMapCreated = {},
        onMapPublished = { throw expectedFailure },
        onMapClosing = { map ->
          finalizerWasOnOwner.store(loop.isOwnerThread())
          finalizerSawPublishedMap.store(loop.map === map)
          finalized.countDown()
          finalizerReleased.complete(releaseFinalizer.await(TimeoutMillis))
        },
        onEvent = { _, _ -> },
        onEventsDrained = {},
        requestFrame = {},
        onFailure = { failed.countDown() },
      )
    try {
      loop.start()
      assertTrue(failed.await(TimeoutMillis), "the loop did not report its failure")

      loop.close()

      assertTrue(finalized.await(TimeoutMillis), "the loop did not run its finalizer")
      val closed = async(start = CoroutineStart.UNDISPATCHED) { loop.awaitClosed() }
      assertFalse(closed.isCompleted, "Owner cleanup has not completed")
      var refused = false
      loop.submit(onDropped = { refused = true }) {}
      assertTrue(refused, "a closing loop accepted work")
      releaseFinalizer.countDown()
      assertTrue(
        finalizerReleased.await(),
        "close blocked the caller until the finalizer timed out",
      )
      withTimeout(TimeoutMillis) { closed.await() }
      assertTrue(finalizerWasOnOwner.load(), "the finalizer ran outside the map owner thread")
      assertTrue(finalizerSawPublishedMap.load(), "the finalizer ran after the map was unpublished")
      assertSame(expectedFailure, loop.failure)
    } finally {
      releaseFinalizer.countDown()
      loop.close()
      withTimeout(TimeoutMillis) { loop.awaitClosed() }
      owner.close()
      withTimeout(TimeoutMillis) { owner.awaitClosed() }
      FfiTestPlatform.deleteCacheFile(cacheFile)
    }
  }

  @Test
  fun a_queued_read_cannot_overtake_a_synchronous_calls_native_render_update(): MapTestResult =
    runMapTest {
      FfiTestPlatform.initialize()
      val cacheFile = FfiTestPlatform.createCacheFile()
      val owner = MlnFfiRuntime(MlnFfiRuntimeOptions(cacheFile)).also { it.start() }
      val published = TestLatch(1)
      val readFinished = TestLatch(1)
      val renderUpdateSeen = AtomicBoolean(false)
      val readSawRenderUpdate = AtomicBoolean(false)
      val loop =
        MlnFfiMapRuntimeLoop(
          extent = MapExtent.fromLogical(1, 1, 1.0),
          owner = owner,
          getLogger = { MapLog },
          onMapCreated = {},
          onMapPublished = { published.countDown() },
          onEvent = { _, event ->
            if (event.type == RuntimeEventType.MAP_RENDER_UPDATE_AVAILABLE)
              renderUpdateSeen.store(true)
          },
          onEventsDrained = {},
          requestFrame = {},
        )
      try {
        loop.start()
        assertTrue(published.await(TimeoutMillis))
        assertNotNull(
          loop.readBlocking { map ->
            renderUpdateSeen.store(false)
            map.requestRepaint()
            // Already queued when this call ends: no thread-scheduling gap can rescue the drain.
            queueFromAnotherThread(loop) {
              it.styleLayerIds()
              readSawRenderUpdate.store(renderUpdateSeen.load())
              readFinished.countDown()
            }
          }
        )
        assertTrue(readFinished.await(TimeoutMillis), "the queued read did not run")
        assertTrue(readSawRenderUpdate.load(), "the queued read ran before native render feedback")
        val nextReadFinished = TestLatch(1)
        assertNotNull(
          loop.await { map ->
            renderUpdateSeen.store(false)
            map.requestRepaint()
            queueFromAnotherThread(loop) {
              readSawRenderUpdate.store(renderUpdateSeen.load())
              nextReadFinished.countDown()
            }
          }
        )
        assertTrue(nextReadFinished.await(TimeoutMillis))
        assertTrue(readSawRenderUpdate.load(), "the queued read overtook the awaited call's events")
      } finally {
        loop.close()
        withTimeout(TimeoutMillis) { loop.awaitClosed() }
        owner.close()
        withTimeout(TimeoutMillis) { owner.awaitClosed() }
        FfiTestPlatform.deleteCacheFile(cacheFile)
      }
    }

  /** Submits [action] from a worker and waits until it is queued; submit is inline on the owner. */
  private fun queueFromAnotherThread(loop: MlnFfiMapRuntimeLoop, action: (MapHandle) -> Unit) {
    val queued = TestLatch(1)
    launchTestTask {
      loop.submit(action = action)
      queued.countDown()
    }
    check(queued.await(TimeoutMillis)) { "the worker did not queue its action" }
  }

  @Test
  fun cancelled_owner_work_is_skipped_and_nested_submit_runs_inline(): MapTestResult = runMapTest {
    FfiTestPlatform.initialize()
    val cacheFile = FfiTestPlatform.createCacheFile()
    val owner = MlnFfiRuntime(MlnFfiRuntimeOptions(cacheFile)).also { it.start() }
    val published = TestLatch(1)
    val parked = TestLatch(1)
    val release = TestLatch(1)
    val ran = AtomicBoolean(false)
    val loop =
      MlnFfiMapRuntimeLoop(
        extent = MapExtent.fromLogical(1, 1, 1.0),
        owner = owner,
        getLogger = { MapLog },
        onMapCreated = {},
        onMapPublished = { published.countDown() },
        onEvent = { _, _ -> },
        onEventsDrained = {},
        requestFrame = {},
      )
    try {
      loop.start()
      assertTrue(published.await(TimeoutMillis))
      loop.submit {
        parked.countDown()
        check(release.await(TimeoutMillis)) { "caller blocked instead of suspending" }
      }
      assertTrue(parked.await(TimeoutMillis))
      val cancelled =
        launch(start = CoroutineStart.UNDISPATCHED) {
          loop.await { ran.store(true) }
        }
      assertFalse(cancelled.isCompleted)
      cancelled.cancelAndJoin()
      release.countDown()
      assertNotNull(
        loop.await {
          var nestedRan = false
          loop.submit { nestedRan = true }
          assertTrue(nestedRan, "nested submit must finish before the owner call returns")
        }
      )
      assertFalse(ran.load())
      loop.close()
      assertEquals(null, loop.await { true })
    } finally {
      release.countDown()
      loop.close()
      withTimeout(TimeoutMillis) { loop.awaitClosed() }
      owner.close()
      withTimeout(TimeoutMillis) { owner.awaitClosed() }
      FfiTestPlatform.deleteCacheFile(cacheFile)
    }
  }

  @Test
  fun work_submitted_before_the_loop_starts_runs_in_submission_order(): MapTestResult =
    withLoop(start = false) { loop, _ ->
      val order = RecordingList<String>()
      loop.submit { order += "first" }
      val awaited = async(start = CoroutineStart.UNDISPATCHED) { loop.await { order += "second" } }
      loop.submit { order += "third" }
      loop.start()
      loop.submit { order += "after start" }
      assertNotNull(withTimeout(TimeoutMillis) { awaited.await() })
      assertNotNull(loop.await { order += "last" })
      assertEquals(listOf("first", "second", "third", "after start", "last"), order.toList())
    }

  @Test
  fun a_throwing_ordered_submit_releases_its_waiter_and_the_loop_keeps_running(): MapTestResult =
    withLoop { loop, _ ->
      val released = TestLatch(1)
      loop.submit(ordered = true, onDropped = released::countDown) {
        throw IllegalStateException("expected failure")
      }
      assertTrue(released.await(TimeoutMillis), "a throwing action did not run onDropped")
      assertEquals(true, loop.await { true }, "the loop stopped after a failed task")
    }

  @Test
  fun a_throwing_inline_ordered_submit_still_ends_its_batch(): MapTestResult =
    withLoop { loop, events ->
      assertNotNull(loop.await {})
      val released = TestLatch(1)
      val readFinished = TestLatch(1)
      val readSawRenderUpdate = AtomicBoolean(false)
      loop.submit {
        events.clear()
        runCatching {
          loop.submit(ordered = true, onDropped = released::countDown) { map ->
            map.requestRepaint()
            queueFromAnotherThread(loop) {
              readSawRenderUpdate.store(
                RuntimeEventType.MAP_RENDER_UPDATE_AVAILABLE.toString() in events.toList()
              )
              readFinished.countDown()
            }
            throw IllegalStateException("expected failure")
          }
        }
      }
      assertTrue(released.await(TimeoutMillis), "a throwing action did not run onDropped")
      assertTrue(readFinished.await(TimeoutMillis), "the queued read did not run")
      assertTrue(
        readSawRenderUpdate.load(),
        "work queued behind a failed ordered submit ran before its events",
      )
    }

  @Test
  fun await_returns_null_without_running_once_the_loop_has_stopped(): MapTestResult =
    withLoop { loop, _ ->
      loop.close()
      withTimeout(TimeoutMillis) { loop.awaitClosed() }
      var ran = false
      assertNull(loop.await { ran = true })
      assertNull(loop.await(cancellable = false) { ran = true })
      assertFalse(ran)
      assertFailsWith<IllegalStateException> { loop.awaitEventsDrained() }
    }

  @Test
  fun awaited_event_drain_runs_after_the_events_of_earlier_work(): MapTestResult =
    withLoop { loop, events ->
      assertNotNull(loop.await {})
      events.clear()
      loop.submit { map ->
        events += "submitted"
        map.requestRepaint()
      }
      loop.awaitEventsDrained { events += "drained" }
      val order = events.toList()
      assertEquals("drained", order.last())
      assertTrue(
        RuntimeEventType.MAP_RENDER_UPDATE_AVAILABLE.toString() in
          order.subList(order.indexOf("submitted"), order.size),
        "the drain ran before the submitted work's render update: $order",
      )
    }

  /** Runs [block] against a loop whose events are recorded by type, then closes the loop. */
  private fun withLoop(
    start: Boolean = true,
    block: suspend CoroutineScope.(MlnFfiMapRuntimeLoop, RecordingList<String>) -> Unit,
  ): MapTestResult = runMapTest {
    FfiTestPlatform.initialize()
    val cacheFile = FfiTestPlatform.createCacheFile()
    val owner = MlnFfiRuntime(MlnFfiRuntimeOptions(cacheFile)).also { it.start() }
    val events = RecordingList<String>()
    val loop =
      MlnFfiMapRuntimeLoop(
        extent = MapExtent.fromLogical(1, 1, 1.0),
        owner = owner,
        getLogger = { MapLog },
        onMapCreated = {},
        onEvent = { _, event -> events += event.type.toString() },
        onEventsDrained = {},
        requestFrame = {},
      )
    try {
      if (start) loop.start()
      block(loop, events)
    } finally {
      loop.close()
      withTimeout(TimeoutMillis) { loop.awaitClosed() }
      owner.close()
      withTimeout(TimeoutMillis) { owner.awaitClosed() }
      FfiTestPlatform.deleteCacheFile(cacheFile)
    }
  }

  private companion object {
    const val TimeoutMillis = 5_000L
  }
}
