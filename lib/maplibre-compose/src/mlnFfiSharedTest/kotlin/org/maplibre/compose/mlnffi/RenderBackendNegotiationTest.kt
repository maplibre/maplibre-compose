package org.maplibre.compose.mlnffi

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs

class RenderBackendNegotiationTest {

  private fun negotiate(
    runtimeBackends: Set<MapRenderBackend>,
    hostBackends: Set<RenderBackendPair>,
  ): BackendSelection =
    selectBackends(
      runtimeBackends = runtimeBackends,
      hostBackends = hostBackends,
      hostDescription = "fake test host",
      operatingSystem = "Linux",
      architecture = "amd64",
    )

  @Test
  fun selects_the_pair_both_the_runtime_and_the_host_support() {
    val selection =
      negotiate(
        runtimeBackends = setOf(MapRenderBackend.VULKAN),
        hostBackends =
          setOf(RenderBackendPair(MapRenderBackend.VULKAN, ComposeRenderBackend.OPENGL)),
      )

    val selected = assertIs<BackendSelection.Selected>(selection)
    assertEquals(MapRenderBackend.VULKAN, selected.backends.producer)
    assertEquals(ComposeRenderBackend.OPENGL, selected.backends.consumer)
  }

  @Test
  fun ignores_host_backends_the_runtime_was_not_built_with() {
    val selection =
      negotiate(
        runtimeBackends = setOf(MapRenderBackend.OPENGL),
        hostBackends =
          setOf(
            RenderBackendPair(MapRenderBackend.VULKAN, ComposeRenderBackend.OPENGL),
            RenderBackendPair(MapRenderBackend.OPENGL, ComposeRenderBackend.OPENGL),
          ),
      )

    val selected = assertIs<BackendSelection.Selected>(selection)
    assertEquals(MapRenderBackend.OPENGL, selected.backends.producer)
  }

  @Test
  fun prefers_metal_then_vulkan_then_opengl_when_several_are_usable() {
    val allPairs =
      setOf(
        RenderBackendPair(MapRenderBackend.OPENGL, ComposeRenderBackend.OPENGL),
        RenderBackendPair(MapRenderBackend.VULKAN, ComposeRenderBackend.OPENGL),
        RenderBackendPair(MapRenderBackend.METAL, ComposeRenderBackend.METAL),
      )

    assertEquals(
      MapRenderBackend.METAL,
      assertIs<BackendSelection.Selected>(
          negotiate(
            setOf(MapRenderBackend.METAL, MapRenderBackend.VULKAN, MapRenderBackend.OPENGL),
            allPairs,
          )
        )
        .backends
        .producer,
    )

    assertEquals(
      MapRenderBackend.VULKAN,
      assertIs<BackendSelection.Selected>(
          negotiate(setOf(MapRenderBackend.VULKAN, MapRenderBackend.OPENGL), allPairs)
        )
        .backends
        .producer,
    )
  }

  @Test
  fun reports_a_missing_runtime_dependency_when_no_runtime_is_loaded() {
    val selection =
      negotiate(
        runtimeBackends = emptySet(),
        hostBackends =
          setOf(RenderBackendPair(MapRenderBackend.VULKAN, ComposeRenderBackend.OPENGL)),
      )

    val diagnostic = assertIs<BackendSelection.Unavailable>(selection).diagnostic
    assertContains(diagnostic, "No MapLibre Native FFI runtime is on the classpath")
    assertContains(diagnostic, "maplibre-compose-runtime-<backend>-<os>-<arch>")
  }

  @Test
  fun reports_the_host_when_the_host_can_bridge_nothing() {
    val selection =
      negotiate(runtimeBackends = setOf(MapRenderBackend.VULKAN), hostBackends = emptySet())

    val diagnostic = assertIs<BackendSelection.Unavailable>(selection).diagnostic
    assertContains(diagnostic, "No MapLibre backend can be bridged into fake test host")
  }

  @Test
  fun names_both_sides_when_the_runtime_and_host_do_not_overlap() {
    val selection =
      negotiate(
        runtimeBackends = setOf(MapRenderBackend.METAL),
        hostBackends =
          setOf(RenderBackendPair(MapRenderBackend.VULKAN, ComposeRenderBackend.OPENGL)),
      )

    val diagnostic = assertIs<BackendSelection.Unavailable>(selection).diagnostic
    assertContains(diagnostic, "METAL")
    assertContains(diagnostic, "VULKAN")
  }

  @Test
  fun diagnostic_always_identifies_the_machine_and_both_backend_sets() {
    val selection = negotiate(emptySet(), emptySet())

    val diagnostic = assertIs<BackendSelection.Unavailable>(selection).diagnostic
    assertContains(diagnostic, "operating system: Linux (amd64)")
    assertContains(diagnostic, "FFI runtime backends: none")
    assertContains(diagnostic, "Compose host: fake test host")
    assertContains(diagnostic, "bridgeable backends: none")
  }
}
