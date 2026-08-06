package org.maplibre.compose.desktop

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs

class DesktopBackendNegotiationTest {

  private fun negotiate(
    runtimeBackends: Set<MapRenderBackend>,
    hostBackends: Set<DesktopBackendPair>,
  ): BackendSelection =
    selectBackends(
      runtimeBackends = runtimeBackends,
      hostBackends = hostBackends,
      hostDescription = "fake test host",
      operatingSystem = "Linux",
      architecture = "amd64",
    )

  @Test
  fun `selects the pair both the runtime and the host support`() {
    val selection =
      negotiate(
        runtimeBackends = setOf(MapRenderBackend.VULKAN),
        hostBackends =
          setOf(DesktopBackendPair(MapRenderBackend.VULKAN, ComposeRenderBackend.OPENGL)),
      )

    val selected = assertIs<BackendSelection.Selected>(selection)
    assertEquals(MapRenderBackend.VULKAN, selected.backends.producer)
    assertEquals(ComposeRenderBackend.OPENGL, selected.backends.consumer)
  }

  @Test
  fun `ignores host backends the runtime was not built with`() {
    val selection =
      negotiate(
        runtimeBackends = setOf(MapRenderBackend.OPENGL),
        hostBackends =
          setOf(
            DesktopBackendPair(MapRenderBackend.VULKAN, ComposeRenderBackend.OPENGL),
            DesktopBackendPair(MapRenderBackend.OPENGL, ComposeRenderBackend.OPENGL),
          ),
      )

    val selected = assertIs<BackendSelection.Selected>(selection)
    assertEquals(MapRenderBackend.OPENGL, selected.backends.producer)
  }

  @Test
  fun `prefers Metal then Vulkan then OpenGL when several are usable`() {
    val allPairs =
      setOf(
        DesktopBackendPair(MapRenderBackend.OPENGL, ComposeRenderBackend.OPENGL),
        DesktopBackendPair(MapRenderBackend.VULKAN, ComposeRenderBackend.OPENGL),
        DesktopBackendPair(MapRenderBackend.METAL, ComposeRenderBackend.METAL),
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
  fun `reports a missing runtime dependency when no runtime is loaded`() {
    val selection =
      negotiate(
        runtimeBackends = emptySet(),
        hostBackends =
          setOf(DesktopBackendPair(MapRenderBackend.VULKAN, ComposeRenderBackend.OPENGL)),
      )

    val diagnostic = assertIs<BackendSelection.Unavailable>(selection).diagnostic
    assertContains(diagnostic, "No MapLibre Native FFI runtime is on the classpath")
    assertContains(diagnostic, "maplibre-native-ffi-runtime")
  }

  @Test
  fun `reports the host when the host can bridge nothing`() {
    val selection =
      negotiate(runtimeBackends = setOf(MapRenderBackend.VULKAN), hostBackends = emptySet())

    val diagnostic = assertIs<BackendSelection.Unavailable>(selection).diagnostic
    assertContains(diagnostic, "No MapLibre backend can be bridged into fake test host")
  }

  @Test
  fun `names both sides when the runtime and host do not overlap`() {
    val selection =
      negotiate(
        runtimeBackends = setOf(MapRenderBackend.METAL),
        hostBackends =
          setOf(DesktopBackendPair(MapRenderBackend.VULKAN, ComposeRenderBackend.OPENGL)),
      )

    val diagnostic = assertIs<BackendSelection.Unavailable>(selection).diagnostic
    assertContains(diagnostic, "METAL")
    assertContains(diagnostic, "VULKAN")
  }

  @Test
  fun `diagnostic always identifies the machine and both backend sets`() {
    val selection = negotiate(emptySet(), emptySet())

    val diagnostic = assertIs<BackendSelection.Unavailable>(selection).diagnostic
    assertContains(diagnostic, "operating system: Linux (amd64)")
    assertContains(diagnostic, "FFI runtime backends: none")
    assertContains(diagnostic, "Compose host: fake test host")
    assertContains(diagnostic, "bridgeable backends: none")
  }
}
