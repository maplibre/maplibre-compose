package org.maplibre.compose.mlnffi

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import org.maplibre.compose.map.MapExtent

class IosMlnFfiSurfaceControllerTest {
  @Test
  fun close_releases_the_surface_once_before_late_uikit_callbacks() {
    val renderer = RecordingRenderer()
    IosMlnFfiSurfaceController(renderer, logger = null, onFailure = { throw it }).use { controller
      ->
      controller.surfaceLayoutChanged(1L, MapExtent.fromLogical(32, 32, 1.0))
      controller.close()
      controller.surfaceDestroyed()
      controller.close()

      assertEquals(listOf("available", "resized", "lost"), renderer.events)
    }
  }

  @Test
  fun destruction_before_layout_does_not_prevent_a_later_surface() {
    val renderer = RecordingRenderer()
    IosMlnFfiSurfaceController(renderer, logger = null, onFailure = { throw it }).use { controller
      ->
      controller.surfaceDestroyed()
      controller.surfaceLayoutChanged(1L, MapExtent.fromLogical(32, 32, 1.0))
      controller.surfaceDestroyed()

      assertEquals(listOf("available", "resized", "lost"), renderer.events)
    }
  }

  @Test
  fun terminal_failure_reports_to_the_owner_without_closing_its_map() {
    val expected = IllegalStateException("deliberate surface failure")
    var failure: Throwable? = null
    val renderer =
      object : MlnFfiMapRenderer {
        override val backend = MapRenderBackend.METAL

        override fun onSurfaceAvailable(session: MlnFfiMapHostSession) {
          throw expected
        }

        override fun onSurfaceChanged(extent: MapExtent) = Unit

        override fun onSurfaceLost() = Unit

        override fun render(frame: MlnFfiMapFrame) = MlnFfiFrameResult.SKIPPED

        override fun close() {
          error("The presentation owner must retain control of the map")
        }
      }
    IosMlnFfiSurfaceController(renderer, logger = null, onFailure = { failure = it }).use {
      controller ->
      controller.surfaceLayoutChanged(1L, MapExtent.fromLogical(32, 32, 1.0))
      controller.close()
      assertSame(expected, failure)
    }
  }

  private class RecordingRenderer : MlnFfiMapRenderer {
    val events = mutableListOf<String>()
    override val backend = MapRenderBackend.METAL

    override fun onSurfaceAvailable(session: MlnFfiMapHostSession) {
      events += "available"
    }

    override fun onSurfaceChanged(extent: MapExtent) {
      events += "resized"
    }

    override fun onSurfaceLost() {
      events += "lost"
    }

    override fun render(frame: MlnFfiMapFrame) = MlnFfiFrameResult.SKIPPED

    override fun close() = Unit
  }
}
