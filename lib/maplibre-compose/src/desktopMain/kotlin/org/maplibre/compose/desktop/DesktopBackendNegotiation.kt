package org.maplibre.compose.desktop

/** Order in which producer backends are chosen when more than one is usable. */
internal val PRODUCER_BACKEND_PREFERENCE: List<MapRenderBackend> =
  listOf(MapRenderBackend.METAL, MapRenderBackend.VULKAN, MapRenderBackend.OPENGL)

/** The outcome of intersecting the loaded FFI runtime's backends with a host factory's. */
internal sealed interface BackendSelection {
  data class Selected(val backends: DesktopBackendPair) : BackendSelection

  data class Unavailable(val diagnostic: String) : BackendSelection
}

/**
 * Picks the backend pair to render with, or explains why there isn't one. Kept free of FFI and
 * Compose types so it can be tested without a graphics stack.
 *
 * @param runtimeBackends what the loaded FFI runtime was built with, from
 *   `Maplibre.supportedRenderBackends()`
 * @param factory the host factory selected through [LocalDesktopMapHostFactory]
 * @param operatingSystem for diagnostics, e.g. the `os.name` system property
 * @param architecture for diagnostics, e.g. the `os.arch` system property
 */
internal fun selectBackends(
  runtimeBackends: Set<MapRenderBackend>,
  factory: DesktopMapHostFactory,
  operatingSystem: String,
  architecture: String,
): BackendSelection {
  val hostBackends = factory.supportedBackends
  val usable = hostBackends.filter { it.producer in runtimeBackends }

  val selected = PRODUCER_BACKEND_PREFERENCE.firstNotNullOfOrNull { preferred ->
    usable.firstOrNull { it.producer == preferred }
  }

  if (selected != null) return BackendSelection.Selected(selected)

  return BackendSelection.Unavailable(
    buildBackendDiagnostic(runtimeBackends, factory, operatingSystem, architecture)
  )
}

/** Explains why no backend pair was usable, and which dependency is most likely missing. */
private fun buildBackendDiagnostic(
  runtimeBackends: Set<MapRenderBackend>,
  factory: DesktopMapHostFactory,
  operatingSystem: String,
  architecture: String,
): String {
  val hostBackends = factory.supportedBackends

  val cause =
    when {
      runtimeBackends.isEmpty() && hostBackends.isEmpty() ->
        "No MapLibre Native FFI runtime is on the classpath and ${factory.description} cannot " +
          "bridge any backend on this machine."
      runtimeBackends.isEmpty() ->
        "No MapLibre Native FFI runtime is on the classpath. Add a runtimeOnly dependency on " +
          "org.maplibre.nativeffi:maplibre-native-ffi-runtime-<backend>-jvm with the " +
          "natives-<os>-<arch> classifier for this platform."
      hostBackends.isEmpty() ->
        "${factory.description} cannot bridge any backend on this machine. If you supplied a " +
          "custom DesktopMapHostFactory, check its supportedBackends."
      else ->
        "The packaged MapLibre Native FFI runtime renders with " +
          "${runtimeBackends.describe()}, but ${factory.description} can only bridge " +
          "${hostBackends.map { it.producer }.toSet().describe()}. Package the runtime matching " +
          "a backend the host can bridge."
    }

  return buildString {
    appendLine("MapLibre Compose could not select a desktop render backend.")
    appendLine(cause)
    appendLine("  operating system: $operatingSystem ($architecture)")
    appendLine("  FFI runtime backends: ${runtimeBackends.describe()}")
    appendLine("  host factory: ${factory.description}")
    append("  host factory backends: ")
    append(
      if (hostBackends.isEmpty()) "none"
      else hostBackends.sortedBy { it.toString() }.joinToString { it.toString() }
    )
  }
}

private fun Set<*>.describe(): String =
  if (isEmpty()) "none" else sortedBy { it.toString() }.joinToString { it.toString() }
