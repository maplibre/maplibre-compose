package org.maplibre.compose.desktop

import androidx.compose.runtime.Immutable
import java.nio.file.Path
import java.nio.file.Paths
import org.maplibre.compose.mlnffi.MlnFfiRuntimeOptions

/**
 * Process-wide configuration for MapLibre Native on desktop.
 *
 * Install it once with [MapLibre.configure] before opening any maps. Repeating the same
 * configuration is harmless; attempting to replace it fails.
 */
@Immutable
public data class DesktopRuntimeOptions(
  /**
   * Where the ambient resource cache and offline-region database live.
   *
   * This path must be private to the application. Use [desktopCachePath] to place it under the
   * current operating system's per-user cache directory.
   */
  public val cachePath: Path,

  /**
   * Maximum ambient cache size in bytes, or null for MapLibre's own default.
   *
   * This bounds only the ambient cache — tiles kept opportunistically as the user pans. Offline
   * regions are not ambient and are not evicted to satisfy it.
   */
  public val maximumCacheSizeBytes: Long? = null,
)

/**
 * Returns the conventional desktop cache database path for [applicationId].
 *
 * [applicationId] is used only as a filesystem namespace and should be a stable reverse-domain
 * identifier such as `com.example.myapp`.
 */
public fun desktopCachePath(applicationId: String): Path {
  require(APPLICATION_ID.matches(applicationId)) {
    "applicationId must contain only nonempty dot-separated letters, digits, underscores, or hyphens"
  }
  val os = System.getProperty("os.name")?.lowercase().orEmpty()
  val home = System.getProperty("user.home") ?: "."
  val base =
    System.getenv("XDG_CACHE_HOME")?.takeIf { it.isNotBlank() }?.let(Paths::get)
      ?: when {
        os.contains("mac") -> Paths.get(home, "Library", "Caches")
        os.contains("windows") ->
          System.getenv("LOCALAPPDATA")?.let(Paths::get) ?: Paths.get(home, "AppData", "Local")
        else -> Paths.get(home, ".cache")
      }
  return base.resolve(applicationId).resolve("maplibre-cache.db")
}

internal fun DesktopRuntimeOptions.toMlnFfiRuntimeOptions(): MlnFfiRuntimeOptions =
  MlnFfiRuntimeOptions(cachePath, maximumCacheSizeBytes)

private val APPLICATION_ID = Regex("[A-Za-z0-9_-]+(?:\\.[A-Za-z0-9_-]+)*")
