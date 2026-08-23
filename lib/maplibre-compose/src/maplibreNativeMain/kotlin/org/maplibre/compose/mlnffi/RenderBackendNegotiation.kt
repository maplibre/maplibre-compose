package org.maplibre.compose.mlnffi

/**
 * Validates the platform host's exact backend pair against the loaded FFI runtime.
 *
 * @param runtimeBackends what the loaded FFI runtime was built with, from
 *   `Maplibre.supportedRenderBackends()`
 * @param hostBackends what the bridge into the Compose host carries
 * @param hostDescription names the host in diagnostics
 * @param operatingSystem for diagnostics, e.g. the `os.name` system property
 * @param architecture for diagnostics, e.g. the `os.arch` system property
 */
internal fun backendDiagnostic(
  runtimeBackends: Set<MapRenderBackend>,
  hostBackends: RenderBackendPair,
  hostDescription: String,
  operatingSystem: String,
  architecture: String,
): String? {
  if (hostBackends.producer in runtimeBackends) return null
  val cause =
    when {
      runtimeBackends.isEmpty() ->
        "No MapLibre Native FFI runtime is on the classpath. Add a runtimeOnly dependency on " +
          "the matching org.maplibre.compose:maplibre-compose-runtime-<backend>-<os>-<arch> " +
          "artifact for this platform, or maplibre-compose-runtime-<backend>-android on Android."
      else ->
        "The packaged MapLibre Native FFI runtime renders with " +
          "${runtimeBackends.describe()}, but $hostDescription requires ${hostBackends.producer}. " +
          "Package the runtime matching the host."
    }

  return buildString {
    appendLine("MapLibre Compose could not use the host render backend.")
    appendLine(cause)
    appendLine("  operating system: $operatingSystem ($architecture)")
    appendLine("  FFI runtime backends: ${runtimeBackends.describe()}")
    appendLine("  Compose host: $hostDescription")
    append("  required bridge: $hostBackends")
  }
}

private fun Set<*>.describe(): String =
  if (isEmpty()) "none" else sortedBy { it.toString() }.joinToString { it.toString() }
