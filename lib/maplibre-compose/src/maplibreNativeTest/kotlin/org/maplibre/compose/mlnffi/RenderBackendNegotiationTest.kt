package org.maplibre.compose.mlnffi

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertNull

class RenderBackendNegotiationTest {
  private val pair = RenderBackendPair(MapRenderBackend.VULKAN, ComposeRenderBackend.OPENGL)

  private fun diagnostic(runtimeBackends: Set<MapRenderBackend>): String? =
    backendDiagnostic(
      runtimeBackends = runtimeBackends,
      hostBackends = pair,
      hostDescription = "fake test host",
      operatingSystem = "Linux",
      architecture = "amd64",
    )

  @Test
  fun accepts_the_host_pair_when_its_producer_is_packaged() {
    assertNull(diagnostic(setOf(MapRenderBackend.VULKAN)))
  }

  @Test
  fun reports_a_missing_runtime_dependency() {
    val message = checkNotNull(diagnostic(emptySet()))
    assertContains(message, "No MapLibre Native FFI runtime is on the classpath")
    assertContains(message, "maplibre-compose-runtime-<backend>-<platform>")
  }

  @Test
  fun reports_an_exact_pair_mismatch() {
    val message = checkNotNull(diagnostic(setOf(MapRenderBackend.METAL)))
    assertContains(message, "METAL")
    assertContains(message, "VULKAN")
    assertContains(message, "operating system: Linux (amd64)")
    assertContains(message, "Compose host: fake test host")
    assertContains(message, "required bridge: $pair")
  }
}
