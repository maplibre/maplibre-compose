@file:OptIn(ExperimentalAtomicApi::class)

package org.maplibre.compose.map

import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.test.Test
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.maplibre.compose.logging.MapLog
import org.maplibre.compose.mlnffi.FfiTestPlatform
import org.maplibre.compose.mlnffi.TestLatch

class MlnFfiMapRuntimeLoopTest {

  @Test
  fun a_failed_loop_finalizes_the_published_map_on_its_owner_thread() {
    FfiTestPlatform.initialize()
    val cacheFile = FfiTestPlatform.createCacheFile()
    val failed = TestLatch(1)
    val finalized = TestLatch(1)
    val finalizerWasOnOwner = AtomicBoolean(false)
    val finalizerSawPublishedMap = AtomicBoolean(false)
    val expectedFailure = IllegalStateException("stop after publication")
    lateinit var loop: MlnFfiMapRuntimeLoop
    loop =
      MlnFfiMapRuntimeLoop(
        extent = MapExtent.fromLogical(1, 1, 1.0),
        cacheFile = cacheFile,
        getLogger = { MapLog() },
        onMapCreated = {},
        onMapPublished = { throw expectedFailure },
        onMapClosing = { map ->
          finalizerWasOnOwner.store(loop.isOwnerThread())
          finalizerSawPublishedMap.store(loop.map === map)
          finalized.countDown()
        },
        onEvent = {},
        onEventsDrained = {},
        requestFrame = {},
        onFailure = { failed.countDown() },
      )
    try {
      loop.start()
      assertTrue(failed.await(TIMEOUT_MILLIS), "the loop did not report its failure")

      loop.close()

      assertTrue(finalized.await(TIMEOUT_MILLIS), "the loop did not run its finalizer")
      assertTrue(finalizerWasOnOwner.load(), "the finalizer ran outside the map owner thread")
      assertTrue(finalizerSawPublishedMap.load(), "the finalizer ran after the map was unpublished")
      assertSame(expectedFailure, loop.failure)
    } finally {
      runCatching { loop.close() }
      FfiTestPlatform.deleteCacheFile(cacheFile)
    }
  }

  private companion object {
    const val TIMEOUT_MILLIS = 5_000L
  }
}
