package org.maplibre.compose.mlnffi

import android.view.Surface
import org.maplibre.compose.map.MapExtent

/**
 * An Android [Surface] that MapLibre presents to. The session creates the EGL or Vulkan context and
 * keeps its own reference to the surface's window.
 */
internal data class AndroidSurfaceTarget(
  val surface: Surface,
  override val backend: MapRenderBackend,
  override val extent: MapExtent,
  override val generation: Long,
) : PlatformWindowTarget {
  override val window: Any
    get() = surface
}
