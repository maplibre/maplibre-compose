package org.maplibre.compose.mlnffi

import android.view.Surface
import org.maplibre.compose.map.MapExtent

/**
 * The EGL or Vulkan context backing one host [Surface]. The context and its render target handles
 * are closed with the surface.
 */
internal interface AndroidMapGraphicsContext : AutoCloseable {
  /** The render target for a frame at [extent]; [generation] distinguishes reallocations. */
  fun target(extent: MapExtent, generation: Long): MlnFfiRenderTarget

  companion object {
    fun create(backend: MapRenderBackend, surface: Surface): AndroidMapGraphicsContext =
      when (backend) {
        MapRenderBackend.OpenGl -> AndroidEglContext.create(surface)
        MapRenderBackend.Vulkan -> AndroidVulkanContext.create(surface)
        MapRenderBackend.Metal -> error("Metal is not an Android render backend")
      }
  }
}
