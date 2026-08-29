@file:OptIn(ExperimentalAtomicApi::class)

package org.maplibre.compose.map

import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.maplibre.compose.mlnffi.BridgeMapFixture
import org.maplibre.compose.mlnffi.TestLatch
import org.maplibre.compose.mlnffi.launchTestTask
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.MlnFfiStyleBinding

/**
 * A caller's loaded check and its queued owner-thread closure are two moments; a style swap's
 * unload can land between them, and the closure must then drop rather than mutate the new style.
 */
class MlnFfiStaleBindingTest {

  @Test
  fun a_mutation_queued_before_an_unload_is_dropped() {
    val mutationRan = AtomicBoolean(false)
    val mutationAbandoned = AtomicBoolean(false)
    val mutationReturnedNull = AtomicBoolean(false)

    BridgeMapFixture.create().use { fixture ->
      fixture.loadStyle(BaseStyle.Empty)
      val stale = assertIs<MlnFfiStyleBinding>(fixture.style)

      // Backlogs the owner thread so the mutation waits in the queue behind this task.
      val hold = TestLatch(1)
      assertTrue(fixture.core.postOwnerTaskForTest { hold.await() })

      // The loaded pre-check passes here; the queued closure runs only after the swap's unload.
      val queued = TestLatch(1)
      val done = TestLatch(1)
      launchTestTask {
        queued.countDown()
        val result =
          stale.mutateMap(abandon = { mutationAbandoned.store(true) }) { mutationRan.store(true) }
        mutationReturnedNull.store(result == null)
        done.countDown()
      }
      assertTrue(queued.await(30_000), "the mutating task never started")

      fixture.commandStyle(
        BaseStyle.Json("""{"version":8,"name":"swap","sources":{},"layers":[]}""")
      )
      hold.countDown()

      assertTrue(done.await(30_000), "the stale mutation was never released")
      assertFalse(mutationRan.load(), "a stale binding's mutation must not reach the map")
      assertTrue(mutationAbandoned.load(), "the dropped mutation must run its abandon path")
      assertTrue(mutationReturnedNull.load(), "a dropped mutation must answer null")

      fixture.pumpUntil("the swapped style to load") {
        fixture.style != null && fixture.style !== stale
      }
    }
  }
}
