package org.maplibre.compose.mlnffi

import kotlin.test.Test

class IosMlnFfiSurfaceControllerTest {
  private class NoOpRenderer : MlnFfiMapRenderer {
    override val backend = MapRenderBackend.METAL

    override fun render(frame: MlnFfiMapFrame) = MlnFfiFrameResult.SKIPPED

    override fun close() {}
  }

  @Test
  fun `surfaceDestroyed after close does not throw`() {
    // UIKit releases interop views in a deferred transaction that can land after close(); the
    // close teardown has already dropped the render session by then.
    val controller = IosMlnFfiSurfaceController(NoOpRenderer(), logger = null)
    controller.close()
    controller.surfaceDestroyed()
  }

  @Test
  fun `surfaceDestroyed before a surface does not throw`() {
    // UIKitView onRelease can fire without a layout ever reporting an extent.
    val controller = IosMlnFfiSurfaceController(NoOpRenderer(), logger = null)
    controller.surfaceDestroyed()
    controller.close()
  }

  @Test
  fun `double close does not throw`() {
    val controller = IosMlnFfiSurfaceController(NoOpRenderer(), logger = null)
    controller.close()
    controller.close()
  }
}
