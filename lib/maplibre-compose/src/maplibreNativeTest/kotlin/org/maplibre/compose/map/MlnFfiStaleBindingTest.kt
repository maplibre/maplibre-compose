package org.maplibre.compose.map

import kotlin.concurrent.Volatile
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.maplibre.compose.mlnffi.BridgeMapFixture
import org.maplibre.compose.mlnffi.TestLatch
import org.maplibre.compose.mlnffi.launchTestTask
import org.maplibre.compose.mlnffi.parkForTest
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.MlnFfiStyleBinding

/**
 * A caller's loaded check and its queued owner-thread closure are two moments; a style swap's
 * unload can land between them, and the closure must then drop rather than mutate the new style.
 */
class MlnFfiStaleBindingTest {

  @Volatile private var mutationRan = false
  @Volatile private var mutationAbandoned = false
  @Volatile private var mutationReturnedNull = false

  @Test
  fun a_mutation_queued_before_an_unload_is_dropped() {
    BridgeMapFixture.create().use { fixture ->
      fixture.loadStyle(BaseStyle.Empty)
      val stale = assertIs<MlnFfiStyleBinding>(fixture.style)

      // Backlogs the owner thread so the mutation waits in the queue behind this task.
      val hold = TestLatch(1)
      assertTrue(fixture.core.postOwnerTaskForTest { hold.await() })

      // The loaded pre-check passes here; the queued closure runs only after the swap's unload.
      val done = TestLatch(1)
      launchTestTask {
        val result = stale.mutateMap(abandon = { mutationAbandoned = true }) { mutationRan = true }
        mutationReturnedNull = result == null
        done.countDown()
      }
      parkForTest(200)

      fixture.core.setBaseStyle(
        BaseStyle.Json("""{"version":8,"name":"swap","sources":{},"layers":[]}""")
      )
      hold.countDown()

      assertTrue(done.await(30_000), "the stale mutation was never released")
      assertFalse(mutationRan, "a stale binding's mutation must not reach the map")
      assertTrue(mutationAbandoned, "the dropped mutation must run its abandon path")
      assertTrue(mutationReturnedNull, "a dropped mutation must answer null")

      fixture.pumpUntil("the swapped style to load") {
        fixture.style != null && fixture.style !== stale
      }
    }
  }
}
