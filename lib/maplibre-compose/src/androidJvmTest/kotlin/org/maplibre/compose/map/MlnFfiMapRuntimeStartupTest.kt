package org.maplibre.compose.map

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path

class MlnFfiMapRuntimeStartupTest {
  @Test
  fun failure_to_start_the_owner_releases_queued_work_and_acknowledges_cleanup() = runTest {
    val loop =
      MlnFfiMapRuntimeLoop(
        extent = MapExtent.fromLogical(1, 1, 1.0),
        cacheFile = Path("unused"),
        getLogger = { null },
        onMapCreated = { error("The owner must not run") },
        onEvent = { _, _ -> },
        onEventsDrained = {},
        requestFrame = {},
      )
    var abandoned = 0
    assertTrue(loop.post(action = { error("The owner must not run") }, abandon = { abandoned++ }))
    val read =
      async(start = CoroutineStart.UNDISPATCHED) { loop.await { error("The owner must not run") } }
    val failure = IllegalStateException("Cannot create the owner thread")

    assertSame(failure, assertFailsWith<IllegalStateException> { loop.start { throw failure } })

    assertSame(failure, loop.failure)
    assertEquals(1, abandoned)
    assertNull(read.await())
    assertFalse(loop.post({}))
    loop.close()
    val closed = async(start = CoroutineStart.UNDISPATCHED) { loop.awaitClosed() }
    assertTrue(closed.isCompleted, "An owner that never started has no native resources to release")
    closed.await()
    assertEquals(1, abandoned)
  }
}
