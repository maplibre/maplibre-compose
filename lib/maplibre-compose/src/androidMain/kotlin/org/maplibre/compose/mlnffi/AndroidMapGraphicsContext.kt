package org.maplibre.compose.mlnffi

import android.view.Surface
import org.maplibre.compose.map.MapExtent

/**
 * The graphics context one Android map surface presents through, created for a single [Surface] and
 * closed with it. A context names the render target each frame draws into.
 */
internal interface AndroidMapGraphicsContext : AutoCloseable {
  /** The target a frame at [extent] renders into, as allocation [generation] of this context. */
  fun target(extent: MapExtent, generation: Long): MlnFfiRenderTarget

  companion object {
    fun create(backend: MapRenderBackend, surface: Surface): AndroidMapGraphicsContext =
      when (backend) {
        MapRenderBackend.OPENGL -> AndroidEglContext.create(surface)
        MapRenderBackend.VULKAN -> AndroidVulkanContext.create(surface)
        MapRenderBackend.METAL -> error("Metal is not an Android render backend")
      }
  }
}
