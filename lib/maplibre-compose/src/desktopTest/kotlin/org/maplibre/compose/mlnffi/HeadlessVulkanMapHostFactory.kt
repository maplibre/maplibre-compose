package org.maplibre.compose.mlnffi

/**
 * Supplies [HeadlessVulkanMapHost]s to a composition under test. Provide it through
 * `LocalMlnFfiMapHostFactory` and `MaplibreMap` runs its real desktop path with no window.
 */
internal class HeadlessVulkanMapHostFactory : MlnFfiMapHostFactory {

  override val supportedBackends: Set<RenderBackendPair> =
    setOf(RenderBackendPair(MapRenderBackend.VULKAN, ComposeRenderBackend.OPENGL))

  override val description: String = "headless Vulkan test host"

  /** Every host handed out, so a test can assert they were all closed. */
  val created: MutableList<HeadlessVulkanMapHost> = mutableListOf()

  override fun create(producer: MapRenderBackend): MlnFfiMapHostResult {
    check(producer == MapRenderBackend.VULKAN) { "$producer is not supported headlessly" }
    val host = HeadlessVulkanMapHost.create()
    created += host
    return MlnFfiMapHostResult.Created(host)
  }

  companion object {
    /**
     * Creates a factory, probing Vulkan up front so a missing loader fails by name rather than as a
     * surface that never becomes ready. See [HeadlessVulkanMapHost.create].
     */
    fun create(): HeadlessVulkanMapHostFactory {
      HeadlessVulkanMapHost.create().close()
      return HeadlessVulkanMapHostFactory()
    }
  }
}
