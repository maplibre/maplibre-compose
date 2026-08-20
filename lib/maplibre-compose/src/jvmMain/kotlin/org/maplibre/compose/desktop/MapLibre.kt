package org.maplibre.compose.desktop

import java.nio.file.Path
import java.nio.file.Paths
import org.maplibre.compose.mlnffi.MlnFfiApplication
import org.maplibre.compose.mlnffi.MlnFfiRuntimeOptions

/** Process-wide entry point for configuring MapLibre Compose on desktop. */
public object MapLibre {
  /**
   * Configures every map and offline operation in this process.
   *
   * The first call wins. Repeating the same normalized configuration is a no-op; a conflicting call
   * throws [IllegalStateException].
   *
   * @param applicationId Stable reverse-domain application identifier, such as `com.example.myapp`.
   *   MapLibre uses it to isolate the application's cache in the current operating system's
   *   per-user cache directory.
   * @param maximumCacheSizeBytes Maximum ambient cache size in bytes, or null for MapLibre's own
   *   default. Offline regions are not ambient and are not evicted to satisfy this limit.
   */
  public fun configure(applicationId: String, maximumCacheSizeBytes: Long? = null) {
    require(APPLICATION_ID.matches(applicationId)) {
      "applicationId must contain only nonempty dot-separated letters, digits, underscores, or hyphens"
    }
    val cachePath = desktopCachePath(applicationId)
    MlnFfiApplication.configure(
      MlnFfiRuntimeOptions(kotlinx.io.files.Path(cachePath.toString()), maximumCacheSizeBytes)
    )
  }
}

/**
 * Returns the conventional desktop cache database path for [applicationId].
 *
 * [applicationId] is used only as a filesystem namespace and should be a stable reverse-domain
 * identifier such as `com.example.myapp`.
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

private val APPLICATION_ID = Regex("[A-Za-z0-9_-]+(?:\\.[A-Za-z0-9_-]+)*")
