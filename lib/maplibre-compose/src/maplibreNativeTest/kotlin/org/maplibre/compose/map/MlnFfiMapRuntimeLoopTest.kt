@file:OptIn(ExperimentalAtomicApi::class)

package org.maplibre.compose.map

import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.maplibre.compose.logging.MapLog
import org.maplibre.compose.mlnffi.FfiTestPlatform
import org.maplibre.compose.mlnffi.TestLatch
import org.maplibre.compose.testing.MapTestResult
import org.maplibre.compose.testing.runMapTest
import org.maplibre.nativeffi.runtime.RuntimeEventType

class MlnFfiMapRuntimeLoopTest {

  @Test
  fun a_failed_loop_finalizes_the_published_map_on_its_owner_thread() = runBlocking {
    FfiTestPlatform.initialize()
    val cacheFile = FfiTestPlatform.createCacheFile()
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
        cacheFile = cacheFile,
        getLogger = { MapLog },
        onMapCreated = {},
        onMapPublished = { throw expectedFailure },
        onMapClosing = { map ->
          finalizerWasOnOwner.store(loop.isOwnerThread())
          finalizerSawPublishedMap.store(loop.map === map)
          finalized.countDown()
          finalizerReleased.complete(releaseFinalizer.await(TIMEOUT_MILLIS))
        },
        onEvent = { _, _ -> },
        onEventsDrained = {},
        requestFrame = {},
        onFailure = { failed.countDown() },
      )
    try {
      loop.start()
      assertTrue(failed.await(TIMEOUT_MILLIS), "the loop did not report its failure")

      loop.close()

      assertTrue(finalized.await(TIMEOUT_MILLIS), "the loop did not run its finalizer")
      val closed = async(start = CoroutineStart.UNDISPATCHED) { loop.awaitClosed() }
      assertFalse(closed.isCompleted, "Owner cleanup has not completed")
      assertFalse(loop.post({}))
      releaseFinalizer.countDown()
      assertTrue(
        finalizerReleased.await(),
        "close blocked the caller until the finalizer timed out",
      )
      withTimeout(TIMEOUT_MILLIS) { closed.await() }
      assertTrue(finalizerWasOnOwner.load(), "the finalizer ran outside the map owner thread")
      assertTrue(finalizerSawPublishedMap.load(), "the finalizer ran after the map was unpublished")
      assertSame(expectedFailure, loop.failure)
    } finally {
      releaseFinalizer.countDown()
      loop.close()
      withTimeout(TIMEOUT_MILLIS) { loop.awaitClosed() }
      FfiTestPlatform.deleteCacheFile(cacheFile)
    }
  }

  @Test
  fun a_queued_read_cannot_overtake_a_synchronous_calls_native_render_update(): MapTestResult =
    runMapTest {
      FfiTestPlatform.initialize()
      val cacheFile = FfiTestPlatform.createCacheFile()
      val published = TestLatch(1)
      val readFinished = TestLatch(1)
      val renderUpdateSeen = AtomicBoolean(false)
      val readSawRenderUpdate = AtomicBoolean(false)
      val loop =
        MlnFfiMapRuntimeLoop(
          extent = MapExtent.fromLogical(1, 1, 1.0),
          cacheFile = cacheFile,
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
        assertTrue(published.await(TIMEOUT_MILLIS))
        assertNotNull(
          loop.call(
            action = { map ->
              renderUpdateSeen.store(false)
              map.requestRepaint()
              // Already queued when this call ends: no thread-scheduling gap can rescue the drain.
              check(
                loop.post(
                  action = { nextMap ->
                    nextMap.styleLayerIds()
                    readSawRenderUpdate.store(renderUpdateSeen.load())
                    readFinished.countDown()
                  }
                )
              )
            }
          )
        )
        assertTrue(readFinished.await(TIMEOUT_MILLIS), "the queued read did not run")
        assertTrue(readSawRenderUpdate.load(), "the queued read ran before native render feedback")
        val nextReadFinished = TestLatch(1)
        assertNotNull(
          loop.await { map ->
            renderUpdateSeen.store(false)
            map.requestRepaint()
            check(
              loop.post({
                readSawRenderUpdate.store(renderUpdateSeen.load())
                nextReadFinished.countDown()
              })
            )
          }
        )
        assertTrue(nextReadFinished.await(TIMEOUT_MILLIS))
        assertTrue(readSawRenderUpdate.load(), "the queued read overtook the awaited call's events")
      } finally {
        loop.close()
        withTimeout(TIMEOUT_MILLIS) { loop.awaitClosed() }
        FfiTestPlatform.deleteCacheFile(cacheFile)
      }
    }

  @Test
  fun cancelled_owner_work_is_skipped_and_nested_dispatch_runs_inline(): MapTestResult =
    runMapTest {
      FfiTestPlatform.initialize()
      val cacheFile = FfiTestPlatform.createCacheFile()
      val published = TestLatch(1)
      val parked = TestLatch(1)
      val release = TestLatch(1)
      val ran = AtomicBoolean(false)
      val loop =
        MlnFfiMapRuntimeLoop(
          extent = MapExtent.fromLogical(1, 1, 1.0),
          cacheFile = cacheFile,
          getLogger = { MapLog },
          onMapCreated = {},
          onMapPublished = { published.countDown() },
          onEvent = { _, _ -> },
          onEventsDrained = {},
          requestFrame = {},
        )
      try {
        loop.start()
        assertTrue(published.await(TIMEOUT_MILLIS))
        assertTrue(
          loop.post({
            parked.countDown()
            check(release.await(TIMEOUT_MILLIS)) { "caller blocked instead of suspending" }
          })
        )
        assertTrue(parked.await(TIMEOUT_MILLIS))
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
            assertTrue(loop.dispatch({ nestedRan = true }))
            assertTrue(nestedRan, "nested dispatch must finish before the owner call returns")
          }
        )
        assertFalse(ran.load())
        loop.close()
        assertEquals(null, loop.await { true })
      } finally {
        release.countDown()
        loop.close()
        withTimeout(TIMEOUT_MILLIS) { loop.awaitClosed() }
        FfiTestPlatform.deleteCacheFile(cacheFile)
      }
    }

  private companion object {
    const val TIMEOUT_MILLIS = 5_000L
  }
}
