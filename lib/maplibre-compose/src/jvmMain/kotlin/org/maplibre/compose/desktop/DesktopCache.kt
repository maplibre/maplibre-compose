package org.maplibre.compose.desktop

import java.nio.file.Path
import java.nio.file.Paths

/**
 * Cache database path for [applicationId] in the current operating system's per-user cache
 * directory.
 */
internal fun desktopCachePath(applicationId: String): Path =
  desktopUserCacheDirectory().resolve(applicationId).resolve("maplibre-cache.db")

/** The current operating system's per-user cache directory. */
internal fun desktopUserCacheDirectory(): Path {
  val os = System.getProperty("os.name")?.lowercase().orEmpty()
  val home = System.getProperty("user.home") ?: "."
  return absoluteEnvironmentPath(System.getenv("XDG_CACHE_HOME"))
    ?: when {
      os.contains("mac") -> Paths.get(home, "Library", "Caches")
      os.contains("windows") ->
        absoluteEnvironmentPath(System.getenv("LOCALAPPDATA"))
          ?: Paths.get(home, "AppData", "Local")
      else -> Paths.get(home, ".cache")
    }
}

internal fun absoluteEnvironmentPath(value: String?): Path? =
  value?.takeIf { it.isNotBlank() }?.let(Paths::get)?.takeIf(Path::isAbsolute)
