package org.maplibre.compose.mlnffi

import androidx.compose.runtime.Immutable

/**
 * A graphics backend MapLibre Native can render a map with.
 *
 * MapLibre is the *producer*: it renders into a target that Compose later draws. Which backends are
 * available depends on the MapLibre Native FFI runtime the application packaged.
 */
internal enum class MapRenderBackend {
  METAL,
  VULKAN,
  OPENGL,
}

/**
 * A graphics backend Compose can draw with.
 *
 * Compose is the *consumer*: it imports the target MapLibre rendered into and composites it with
 * the rest of the UI. Which backend is in use depends on the Compose host and operating system.
 */
public enum class ComposeRenderBackend {
  METAL,
  DIRECT3D12,
  OPENGL,
}

/**
 * One producer/consumer combination MapLibre Compose can bridge.
 *
 * Support is declared per pair, since bridging Vulkan to OpenGL is different work from bridging
 * Vulkan to Direct3D 12.
 */
@Immutable
internal data class RenderBackendPair(
  /** The backend MapLibre Native renders with. */
  val producer: MapRenderBackend,
  /** The backend Compose draws with. */
  val consumer: ComposeRenderBackend,
) {
  override fun toString(): String = "$producer -> $consumer"
}
