package org.maplibre.compose.desktop

/**
 * Supplies [HeadlessVulkanMapHost]s to a composition under test.
 *
 * Provide it through `LocalDesktopMapHostFactory` and `MaplibreMap` runs its real desktop path —
 * the surface composable, the session, the style, the layers, and MapLibre itself — with no window.
 */
internal class HeadlessVulkanMapHostFactory : DesktopMapHostFactory {

  override val supportedBackends: Set<DesktopBackendPair> =
    setOf(DesktopBackendPair(MapRenderBackend.VULKAN, ComposeRenderBackend.OPENGL))

  override val description: String = "headless Vulkan test host"

  /** Every host handed out, so a test can assert they were all closed. */
  val created: MutableList<HeadlessVulkanMapHost> = mutableListOf()

  override fun create(producer: MapRenderBackend): DesktopMapHostResult {
    if (producer != MapRenderBackend.VULKAN) {
      return DesktopMapHostResult.Unsupported("$producer is not supported headlessly")
    }
    val host = HeadlessVulkanMapHost.create()
    created += host
    return DesktopMapHostResult.Created(host)
  }

  companion object {
    /**
     * Creates a factory, failing if this machine has no usable Vulkan implementation.
     *
     * Probed up front rather than on the first frame, so a missing loader is an error naming itself
     * instead of a surface that silently never becomes ready. See [HeadlessVulkanMapHost.create]
     * for why this fails rather than skipping.
     */
    fun create(): HeadlessVulkanMapHostFactory {
      HeadlessVulkanMapHost.create().close()
      return HeadlessVulkanMapHostFactory()
    }
  }
}
