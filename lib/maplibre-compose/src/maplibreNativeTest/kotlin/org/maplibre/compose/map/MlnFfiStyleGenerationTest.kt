package org.maplibre.compose.map

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.maplibre.compose.mlnffi.BridgeMapFixture
import org.maplibre.compose.mlnffi.TestLatch
import org.maplibre.compose.style.BaseStyle

/**
 * The generation pair behind [MapEngine]'s style wait: `hasLoadedFirstStyle` is sticky, so a
 * snapshot taken right after a base-style change must compare generations to wait for the NEW style
 * instead of sailing past on the old one's load.
 */
class MlnFfiStyleGenerationTest {

  @Test
  fun a_style_change_outdates_the_loaded_generation_until_the_new_style_loads() {
    BridgeMapFixture.create().use { fixture ->
      val core = fixture.core
      assertEquals(0L, core.requestedStyleGeneration)
      assertEquals(0L, core.loadedStyleGeneration)

      fixture.loadStyle(FIRST_STYLE)
      assertTrue(core.hasLoadedFirstStyle)
      assertEquals(core.requestedStyleGeneration, core.loadedStyleGeneration)
      val firstLoaded = core.loadedStyleGeneration

      // The slow-load window, held open deterministically: the owner thread is parked, so the
      // change is requested but the new style cannot have loaded.
      val hold = TestLatch(1)
      assertTrue(core.postOwnerTaskForTest { hold.await() })
      core.setBaseStyle(SECOND_STYLE)
      assertTrue(core.hasLoadedFirstStyle, "the sticky flag alone would skip the wait")
      assertTrue(
        core.loadedStyleGeneration < core.requestedStyleGeneration,
        "a waiter on the generations must see the new style as not yet loaded",
      )

      hold.countDown()
      fixture.pumpUntil("the second style to load") {
        core.loadedStyleGeneration == core.requestedStyleGeneration
      }
      assertTrue(core.loadedStyleGeneration > firstLoaded)

      // Re-selecting the loaded style bumps nothing, so an unchanged style needs no wait.
      core.setBaseStyle(SECOND_STYLE)
      assertEquals(core.requestedStyleGeneration, core.loadedStyleGeneration)
    }
  }

  @Test
  fun a_coalesced_switch_back_to_the_loaded_style_acknowledges_its_generation() {
    BridgeMapFixture.create().use { fixture ->
      val core = fixture.core
      fixture.loadStyle(FIRST_STYLE)
      assertEquals(core.requestedStyleGeneration, core.loadedStyleGeneration)

      // Both requests queue behind the parked owner thread, so the switch coalesces: the second
      // request restores the applied style and no apply, and so no load, ever runs for either.
      val hold = TestLatch(1)
      assertTrue(core.postOwnerTaskForTest { hold.await() })
      core.setBaseStyle(SECOND_STYLE)
      core.setBaseStyle(FIRST_STYLE)
      assertTrue(core.loadedStyleGeneration < core.requestedStyleGeneration)

      hold.countDown()
      fixture.pumpUntil("the coalesced generation to be acknowledged") {
        core.loadedStyleGeneration == core.requestedStyleGeneration
      }
    }
  }

  private companion object {
    val FIRST_STYLE = BaseStyle.Json("""{"version":8,"name":"first","sources":{},"layers":[]}""")

    val SECOND_STYLE = BaseStyle.Json("""{"version":8,"name":"second","sources":{},"layers":[]}""")
  }
}
