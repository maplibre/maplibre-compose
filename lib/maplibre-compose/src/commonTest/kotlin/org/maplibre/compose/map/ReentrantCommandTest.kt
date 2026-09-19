package org.maplibre.compose.map

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlinx.coroutines.test.runTest
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.internal.CameraCommandGuard
import org.maplibre.compose.style.BaseStyle

/**
 * Map state has one writer thread, so a command can only be overtaken by a newer one from inside
 * the adapter write itself. The replay loops exist for that case; these tests pin it.
 */
class ReentrantCommandTest {

  @Test
  fun a_camera_write_that_re_enters_with_a_newer_camera_ends_on_the_newer_one() = runTest {
    val runtime = mapRuntimeForTest(physicalScope = backgroundScope)
    val state = runtime.createMapState(BaseStyle.Demo)
    val second = CameraPosition(zoom = 8.0)
    val adapter =
      object : PresentationTestAdapter() {
        val writes = mutableListOf<CameraPosition>()
        var reenterWith: CameraPosition? = null

        override fun setCameraPosition(cameraPosition: CameraPosition, guard: CameraCommandGuard?) {
          super.setCameraPosition(cameraPosition, guard)
          writes += cameraPosition
          val newer = reenterWith ?: return
          reenterWith = null
          state.setCameraPosition(newer)
        }
      }
    state.publishPresentation(state.reservePresentation(), adapter)
    adapter.writes.clear()
    adapter.reenterWith = second

    state.setCameraPosition(CameraPosition(zoom = 4.0))

    assertEquals(second, state.cameraPosition)
    assertEquals(second, adapter.writes.last())
    state.close()
    state.awaitClosed()
    runtime.close()
  }

  @Test
  fun a_style_write_that_re_enters_with_a_newer_style_ends_on_the_newer_one() = runTest {
    val runtime = mapRuntimeForTest(physicalScope = backgroundScope)
    val state = runtime.createMapState(BaseStyle.Demo)
    val latest = BaseStyle.Json("latest")
    val adapter =
      object : PresentationTestAdapter() {
        val writes = mutableListOf<BaseStyle>()
        private var reentered = false

        override fun setBaseStyle(style: BaseStyle) {
          super.setBaseStyle(style)
          writes += style
          if (!reentered) {
            reentered = true
            checkNotNull(state.style.asMutable).baseStyle = latest
          }
        }
      }

    state.publishPresentation(state.reservePresentation(), adapter)

    assertEquals(latest, state.style.baseStyle)
    assertEquals(latest, adapter.writes.last())
    state.close()
    state.awaitClosed()
    runtime.close()
  }

  @Test
  fun a_presentation_replaced_during_its_configuration_does_not_write_its_style() = runTest {
    val runtime = mapRuntimeForTest(physicalScope = backgroundScope)
    val state = runtime.createMapState(BaseStyle.Demo)
    val owner = MapPresentationOwnerToken()
    val replacement = PresentationTestAdapter()
    val first =
      object : PresentationTestAdapter() {
        var styleWrites = 0
        private var replaced = false

        override fun setCameraPosition(cameraPosition: CameraPosition, guard: CameraCommandGuard?) {
          super.setCameraPosition(cameraPosition, guard)
          if (!replaced) {
            replaced = true
            state.publishPresentation(state.reservePresentation(owner), replacement)
          }
        }

        override fun setBaseStyle(style: BaseStyle) {
          styleWrites++
        }
      }

    state.publishPresentation(state.reservePresentation(owner), first)

    assertEquals(0, first.styleWrites)
    assertSame(replacement, state.currentMapAttachment?.adapter)
    state.close()
    state.awaitClosed()
    runtime.close()
  }
}
