package org.maplibre.compose.desktop.bridge

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.maplibre.compose.mlnffi.FfiTestPlatform
import org.maplibre.compose.mlnffi.ProductionBridgeTestRenderDriver

class ComposeFrameCompletionTest {
  @Test
  fun preservation_and_consumer_completion_precede_native_release() {
    ProductionBridgeTestRenderDriver.create().use { driver ->
      driver.withComposeContext { context ->
        val completion = ComposeFrameCompletion()
        val order = mutableListOf<String>()
        completion.prepare(context.skiaContext) { error("The context did not change") }
        completion.holdFrame { completed ->
          assertTrue(completed)
          order += "release"
        }
        completion.frameRecorded { order += "preserve" }
        assertTrue(completion.hasFrame)
        assertTrue(order.isEmpty())
        completion.prepare(context.skiaContext) { error("The context did not change") }
        assertEquals(listOf("preserve", "release"), order)
        assertFalse(completion.hasFrame)
        completion.prepare(context.skiaContext) { error("The context did not change") }
        completion.abandon()
        assertEquals(listOf("preserve", "release"), order)
      }
    }
  }

  @Test
  fun a_replaced_context_releases_the_old_frame_before_preparing_the_new_context() {
    ProductionBridgeTestRenderDriver.create().use { first ->
      ProductionBridgeTestRenderDriver.create().use { second ->
        first.withComposeContext { previous ->
          second.withComposeContext { next ->
            // The Direct3D 12 environment shares one Compose window, and so one context, per test
            // worker.
            if (previous.skiaContext === next.skiaContext) {
              FfiTestPlatform.skip("This environment has one Compose context to replace")
            }
            val completion = ComposeFrameCompletion()
            val order = mutableListOf<String>()
            completion.prepare(previous.skiaContext) { error("There is no previous context") }
            completion.holdFrame { completed ->
              assertTrue(completed)
              order += "release"
            }
            completion.frameRecorded { error("The lost context cannot replay its frame") }
            completion.prepare(next.skiaContext) { order += "replace" }
            assertEquals(listOf("replace", "release"), order)
            assertFalse(completion.hasFrame)
            completion.abandon()
            assertEquals(listOf("replace", "release"), order)
          }
        }
      }
    }
  }

  @Test
  fun drawing_with_a_replaced_context_drops_its_wrappers_and_keeps_the_frame_held() {
    ProductionBridgeTestRenderDriver.create().use { first ->
      ProductionBridgeTestRenderDriver.create().use { second ->
        first.withComposeContext { previous ->
          second.withComposeContext { next ->
            if (previous.skiaContext === next.skiaContext) {
              FfiTestPlatform.skip("This environment has one Compose context to replace")
            }
            val completion = ComposeFrameCompletion()
            val order = mutableListOf<String>()
            completion.prepare(previous.skiaContext) { error("There is no previous context") }
            completion.holdFrame { completed ->
              assertTrue(completed)
              order += "release"
            }
            completion.frameRecorded { error("The lost context cannot replay its frame") }
            completion.adoptContext(next.skiaContext) { order += "replace" }
            completion.adoptContext(next.skiaContext) { error("The context was already adopted") }
            assertEquals(listOf("replace"), order)
            assertTrue(completion.hasFrame, "Compose still reads the held frame")
            completion.prepare(next.skiaContext) { error("The context was already adopted") }
            assertEquals(listOf("replace", "release"), order)
            completion.abandon()
          }
        }
      }
    }
  }

  @Test
  fun context_loss_releases_held_ownership_once_without_replaying_lost_work() {
    val completion = ComposeFrameCompletion()
    var releases = 0
    completion.holdFrame { completed ->
      assertFalse(completed)
      releases++
    }
    completion.abandon()
    completion.abandon()
    assertFalse(completion.hasFrame)
    assertEquals(1, releases)
  }
}
