package org.maplibre.compose.desktop.bridge

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.maplibre.compose.map.MapExtent
import org.maplibre.compose.mlnffi.MlnFfiMapFrameAcquisition
import org.maplibre.compose.mlnffi.MlnFfiRecoverableFrameException
import org.maplibre.compose.mlnffi.ProductionBridgeTestRenderDriver

class SharedTextureMapHostTest {

  @Test
  fun native_frames_stay_acquired_through_draw_and_release_before_replacement_or_close() {
    val driver = ProductionBridgeTestRenderDriver.create()
    var releases = 0
    try {
      val extent = MapExtent.fromPhysical(64, 64, 1.0)
      val first = assertIs<MlnFfiMapFrameAcquisition.Acquired>(driver.acquireFrame(extent)).frame
      first.releaseAfterReads = { releases++ }
      driver.completeProducerAccess(first)
      driver.releaseFrame(first)
      assertTrue(driver.present(first.target))
      assertEquals(0, releases, "Drawing must not let native write the sampled texture")

      val next =
        assertIs<MlnFfiMapFrameAcquisition.Acquired>(
            driver.acquireFrame(MapExtent.fromPhysical(96, 96, 1.0)),
          )
          .frame
      assertEquals(1, releases, "Replacement must finish the previous frame's reads")
      next.releaseAfterReads = { releases++ }
      driver.completeProducerAccess(next)
      assertTrue(driver.present(next.target))
      assertEquals(1, releases)
    } finally {
      driver.close()
    }
    assertEquals(2, releases, "Close must release the last held frame")
  }

  @Test
  fun a_replaced_texture_stays_presentable_until_a_newer_generation_is_drawn() {
    val released = mutableListOf<String>()
    val textures = SharedTextures<String> { released += it }
    textures.replaceCurrent("first")
    textures.replaceCurrent("second")
    textures.replaceCurrent("third")

    assertEquals(listOf("first", "second", "third"), (1L..3L).map { textures[it] })

    // Compose drew the second generation after the third was allocated.
    textures.releaseRetired(except = 2)
    assertEquals(listOf("first"), released)
    assertEquals("second", textures[2])
    assertEquals("third", textures.current)

    textures.releaseRetired(except = 3)
    assertEquals(listOf("first", "second"), released)
    assertEquals(listOf("third"), textures.all)
  }

  @Test
  fun a_retired_current_texture_is_not_drawn_but_is_kept_until_released() {
    val released = mutableListOf<String>()
    val textures = SharedTextures<String> { released += it }
    textures.replaceCurrent("lost")

    textures.retireCurrent()

    assertNull(textures[textures.generation])
    assertEquals(listOf("lost"), textures.all)
    textures.releaseAll()
    assertEquals(listOf("lost"), released)
  }

  @Test
  fun a_texture_whose_release_fails_is_kept_for_a_retry() {
    val released = mutableListOf<String>()
    var failing = true
    val textures =
      SharedTextures<String> {
        if (it == "second" && failing) error("release failed")
        released += it
      }
    textures.replaceCurrent("first")
    textures.replaceCurrent("second")

    assertFailsWith<IllegalStateException> { textures.releaseAll() }
    assertEquals(listOf("second", "first"), textures.all)

    failing = false
    textures.releaseAll()
    assertEquals(listOf("second", "first"), released)
    assertEquals(emptyList(), textures.all)
  }

  @Test
  fun a_device_change_asks_for_recovery_once_before_replacing_the_device() {
    val recovery = DeviceChangeRecovery<String>("device changed")

    assertFalse(recovery.changed(previous = null, next = "a"))
    assertFalse(recovery.changed(previous = "a", next = "a"))
    assertFailsWith<MlnFfiRecoverableFrameException> { recovery.changed("a", "b") }
    assertTrue(recovery.changed("a", "b"))

    // Returning to the old device forgets the pending change.
    assertFailsWith<MlnFfiRecoverableFrameException> { recovery.changed("a", "b") }
    assertFalse(recovery.changed("a", "a"))
    assertFailsWith<MlnFfiRecoverableFrameException> { recovery.changed("a", "b") }
  }
}
