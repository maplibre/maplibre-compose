package org.maplibre.compose.desktop

import java.nio.file.Path
import java.nio.file.Paths
import org.maplibre.compose.mlnffi.MlnFfiRuntimeOptions
import org.maplibre.compose.resource.MapRequestInterceptor
import org.maplibre.compose.resource.MapResourceProvider

internal fun desktopRuntimeOptions(
  applicationId: String = inferredApplicationId(),
  maximumCacheSizeBytes: Long? = null,
  requestInterceptor: MapRequestInterceptor? = null,
  resourceProvider: MapResourceProvider? = null,
): MlnFfiRuntimeOptions {
  require(APPLICATION_ID.matches(applicationId)) {
    "applicationId must contain only nonempty dot-separated letters, digits, underscores, or hyphens"
  }
  val cachePath = desktopCachePath(applicationId)
  return MlnFfiRuntimeOptions(
    cacheFile = kotlinx.io.files.Path(cachePath.toString()),
    maximumCacheSizeBytes = maximumCacheSizeBytes,
    requestInterceptor = requestInterceptor,
    resourceProvider = resourceProvider,
  )
}

/**
 * Cache database path for [applicationId] in the current operating system's per-user cache
 * directory.
 */
internal fun desktopCachePath(applicationId: String): Path {
  val os = System.getProperty("os.name")?.lowercase().orEmpty()
  val home = System.getProperty("user.home") ?: "."
  val base =
    absoluteEnvironmentPath(System.getenv("XDG_CACHE_HOME"))
      ?: when {
        os.contains("mac") -> Paths.get(home, "Library", "Caches")
        os.contains("windows") ->
          absoluteEnvironmentPath(System.getenv("LOCALAPPDATA"))
            ?: Paths.get(home, "AppData", "Local")
        else -> Paths.get(home, ".cache")
      }
  return base.resolve(applicationId).resolve("maplibre-cache.db")
}

internal fun absoluteEnvironmentPath(value: String?): Path? =
  value?.takeIf { it.isNotBlank() }?.let(Paths::get)?.takeIf(Path::isAbsolute)
