package org.maplibre.compose.mlnffi

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RenderBackendNegotiationTest {
  private val openGlHostBridges =
    listOf(
      RenderBackendPair(MapRenderBackend.OPENGL, ComposeRenderBackend.OPENGL),
      RenderBackendPair(MapRenderBackend.VULKAN, ComposeRenderBackend.OPENGL),
    )

  private fun selected(runtimeBackends: Set<MapRenderBackend>): RenderBackendPair? =
    selectBridge(runtimeBackends, openGlHostBridges)

  private fun diagnostic(runtimeBackends: Set<MapRenderBackend>): String? =
    backendDiagnostic(
      runtimeBackends = runtimeBackends,
      hostBridges = openGlHostBridges,
      hostDescription = "fake test host",
      operatingSystem = "Linux",
      architecture = "amd64",
    )

  @Test
  fun selects_the_first_bridge_the_runtime_drives() {
    assertEquals(openGlHostBridges[0], selected(setOf(MapRenderBackend.OPENGL)))
  }

  @Test
  fun falls_back_to_a_later_bridge_when_the_runtime_does_not_drive_the_first() {
    assertEquals(openGlHostBridges[1], selected(setOf(MapRenderBackend.VULKAN)))
  }

  @Test
  fun selects_among_extra_backends_the_runtime_provides() {
    assertEquals(
      openGlHostBridges[1],
      selected(setOf(MapRenderBackend.VULKAN, MapRenderBackend.METAL)),
    )
  }

  @Test
  fun selects_nothing_when_the_runtime_drives_no_bridge() {
    assertNull(selected(setOf(MapRenderBackend.METAL)))
    assertNull(selected(emptySet()))
  }

  @Test
  fun accepts_a_host_bridge_whose_producer_is_packaged() {
    assertNull(diagnostic(setOf(MapRenderBackend.VULKAN)))
  }

  @Test
  fun reports_a_missing_runtime_dependency() {
    val message = checkNotNull(diagnostic(emptySet()))
    assertContains(message, "No MapLibre Native FFI runtime is on the classpath")
    assertContains(message, "maplibre-compose-runtime-<backend>-<platform>")
  }

  @Test
  fun reports_an_unbridgeable_runtime_backend() {
    val message = checkNotNull(diagnostic(setOf(MapRenderBackend.METAL)))
    assertContains(message, "METAL")
    assertContains(message, "OPENGL -> OPENGL")
    assertContains(message, "VULKAN -> OPENGL")
    assertContains(message, "operating system: Linux (amd64)")
    assertContains(message, "Compose host: fake test host")
    assertContains(message, "available bridges: ${openGlHostBridges.joinToString()}")
  }
}
