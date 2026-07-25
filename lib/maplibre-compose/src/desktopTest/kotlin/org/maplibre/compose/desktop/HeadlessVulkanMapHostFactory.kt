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
    val host =
      HeadlessVulkanMapHost.createOrNull()
        ?: return DesktopMapHostResult.Unsupported("no usable Vulkan implementation")
    created += host
    return DesktopMapHostResult.Created(host)
  }

  companion object {
    /**
     * Creates a factory, or returns null when this machine has no usable Vulkan implementation.
     *
     * Probed up front so a test can skip before composing anything, rather than discovering it as a
     * surface that silently never becomes ready.
     */
    fun createOrNull(): HeadlessVulkanMapHostFactory? {
      val probe = HeadlessVulkanMapHost.createOrNull() ?: return null
      probe.close()
      return HeadlessVulkanMapHostFactory()
    }
  }
}
