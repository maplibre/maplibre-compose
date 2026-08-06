package org.maplibre.compose.mlnffi

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import java.nio.file.Path
import java.nio.file.Paths

/**
 * How a map's MapLibre Native runtime is configured.
 *
 * Provide a different instance through [LocalMlnFfiRuntimeOptions] to move or resize the cache. The
 * options are read once, when the runtime is created; changing them recreates the map.
 */
@Immutable
public data class MlnFfiRuntimeOptions(
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
    public val Default: MlnFfiRuntimeOptions = MlnFfiRuntimeOptions()
  }
}

/** The [MlnFfiRuntimeOptions] maps in this composition use. */
public val LocalMlnFfiRuntimeOptions: ProvidableCompositionLocal<MlnFfiRuntimeOptions> =
  staticCompositionLocalOf {
    MlnFfiRuntimeOptions.Default
  }

/**
 * The per-user cache directory for this platform.
 *
 * `XDG_CACHE_HOME` wins wherever it is set, macOS and Windows included: a user who exports it has
 * said where their caches go, and honouring it only on Linux would ignore that. Otherwise each
 * platform's own convention applies, with the XDG spec's documented fallback last.
 */
private fun defaultCachePath(): Path {
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
  return base.resolve("maplibre-compose").resolve("maplibre-cache.db")
}
