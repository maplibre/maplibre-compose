package org.maplibre.compose.mlnffi

/**
 * The first of [bridges] whose producer the packaged FFI runtime provides, or null when the runtime
 * provides none of them.
 */
internal fun selectBridge(
  runtimeBackends: Set<MapRenderBackend>,
  bridges: List<RenderBackendPair>,
): RenderBackendPair? = bridges.firstOrNull { it.producer in runtimeBackends }

/**
 * Validates the host's available bridges against the loaded FFI runtime.
 *
 * @param runtimeBackends what the loaded FFI runtime was built with, from
 *   `Maplibre.supportedRenderBackends()`
 * @param hostBridges what the bridge into the Compose host can carry, in preference order
 * @param hostDescription names the host in diagnostics
 * @param operatingSystem for diagnostics, e.g. the `os.name` system property
 * @param architecture for diagnostics, e.g. the `os.arch` system property
 */
internal fun backendDiagnostic(
  runtimeBackends: Set<MapRenderBackend>,
  hostBridges: List<RenderBackendPair>,
  hostDescription: String,
  operatingSystem: String,
  architecture: String,
): String? {
  if (selectBridge(runtimeBackends, hostBridges) != null) return null
  val cause =
    when {
      runtimeBackends.isEmpty() ->
        "No MapLibre Native FFI runtime is on the classpath. Add a runtimeOnly dependency on " +
          "the matching org.maplibre.compose:maplibre-compose-runtime-<backend>-<platform> artifact."
      else ->
        "The packaged MapLibre Native FFI runtime renders with ${runtimeBackends.describe()}, " +
          "but $hostDescription bridges only ${hostBridges.describe()}. Package the runtime " +
          "matching one of those bridges."
    }

  return buildString {
    appendLine("MapLibre Compose could not use the host render backend.")
    appendLine(cause)
    appendLine("  operating system: $operatingSystem ($architecture)")
    appendLine("  FFI runtime backends: ${runtimeBackends.describe()}")
    appendLine("  Compose host: $hostDescription")
    append("  available bridges: ${hostBridges.describe()}")
  }
}

private fun Set<*>.describe(): String =
  if (isEmpty()) "none" else sortedBy { it.toString() }.joinToString { it.toString() }

private fun List<*>.describe(): String = joinToString { it.toString() }
