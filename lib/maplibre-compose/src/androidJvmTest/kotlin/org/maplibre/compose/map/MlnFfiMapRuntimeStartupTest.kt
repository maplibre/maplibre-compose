package org.maplibre.compose.map

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import org.maplibre.compose.mlnffi.MlnFfiRuntime
import org.maplibre.compose.mlnffi.MlnFfiRuntimeOptions

class MlnFfiMapRuntimeStartupTest {
  @Test
  fun failure_to_start_the_owner_releases_queued_work_and_acknowledges_cleanup() = runTest {
    val owner = MlnFfiRuntime(MlnFfiRuntimeOptions(Path("unused"), logger = null))
    val loop =
      MlnFfiMapRuntimeLoop(
        extent = MapExtent.fromLogical(1, 1, 1.0),
        owner = owner,
        getLogger = { null },
        onMapCreated = { error("The owner must not run") },
        onEvent = { _, _ -> },
        onEventsDrained = {},
        requestFrame = {},
      )
    var abandoned = 0
    loop.submit(onDropped = { abandoned++ }) { error("The owner must not run") }
    val read =
      async(start = CoroutineStart.UNDISPATCHED) { loop.await { error("The owner must not run") } }
    loop.start()
    val failure = IllegalStateException("Cannot create the owner thread")

    assertSame(failure, assertFailsWith<IllegalStateException> { owner.start { throw failure } })

    assertSame(failure, loop.failure)
    assertEquals(1, abandoned)
    assertNull(read.await())
    var refused = false
    loop.submit(onDropped = { refused = true }) {}
    assertTrue(refused, "A loop that could not start accepted work")
    loop.close()
    val closed = async(start = CoroutineStart.UNDISPATCHED) { loop.awaitClosed() }
    assertTrue(closed.isCompleted, "An owner that never started has no native resources to release")
    closed.await()
    owner.close()
    owner.awaitClosed()
    assertEquals(1, abandoned)
  }
}
