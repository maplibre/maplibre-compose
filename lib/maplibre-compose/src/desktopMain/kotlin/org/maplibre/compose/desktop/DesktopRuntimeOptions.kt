package org.maplibre.compose.desktop

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import java.nio.file.Path
import java.nio.file.Paths

/**
 * How a desktop map's MapLibre runtime is configured.
 *
 * Provide a different instance through [LocalDesktopRuntimeOptions] to move or resize the cache.
 * The options are read once, when the runtime is created; changing them recreates the map.
 */
@Immutable
public data class DesktopRuntimeOptions(
  /**
   * Where the ambient tile and resource cache lives. Defaults to this platform's per-user
   * application data directory, not the working directory.
   */
  public val cachePath: Path = defaultCachePath(),

  /**
   * Maximum ambient cache size in bytes, or null for MapLibre's own default.
   *
   * This bounds only the ambient cache — tiles kept opportunistically as the user pans. Offline
   * regions are not ambient and are not evicted to satisfy it.
   */
  public val maximumCacheSizeBytes: Long? = null,
) {
  public companion object {
    public val Default: DesktopRuntimeOptions = DesktopRuntimeOptions()
  }
}

/** The [DesktopRuntimeOptions] maps in this composition use. */
public val LocalDesktopRuntimeOptions: ProvidableCompositionLocal<DesktopRuntimeOptions> =
  staticCompositionLocalOf {
    DesktopRuntimeOptions.Default
  }

/** The per-user cache directory for this platform, following each platform's own convention. */
private fun defaultCachePath(): Path {
  val os = System.getProperty("os.name")?.lowercase().orEmpty()
  val home = System.getProperty("user.home") ?: "."
  val base =
    when {
      os.contains("mac") -> Paths.get(home, "Library", "Caches")
      os.contains("windows") ->
        System.getenv("LOCALAPPDATA")?.let(Paths::get) ?: Paths.get(home, "AppData", "Local")
      // Linux and anything else: the XDG base directory spec, with its documented fallback.
      else -> System.getenv("XDG_CACHE_HOME")?.let(Paths::get) ?: Paths.get(home, ".cache")
    }
  return base.resolve("maplibre-compose").resolve("maplibre-cache.db")
}
