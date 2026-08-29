package org.maplibre.compose.desktop

import co.touchlab.kermit.Logger
import java.nio.file.Path
import java.nio.file.Paths
import org.maplibre.compose.mlnffi.MlnFfiApplication
import org.maplibre.compose.mlnffi.MlnFfiRuntimeOptions

/** Configuration for one desktop map runtime that the caller closes. */
public data class DesktopRuntimeOptions(
  /** Reverse-domain name for the runtime's cache directory. */
  public val applicationId: String = inferredApplicationId(),
  /** Ambient cache size in bytes, or null for MapLibre's default. */
  public val maximumCacheSizeBytes: Long? = null,

  /** Receives diagnostic messages from maps and shared runtime resources. */
  public val logger: Logger? = Logger.withTag("maplibre-compose"),
)

internal fun DesktopRuntimeOptions.toMlnFfiRuntimeOptions(): MlnFfiRuntimeOptions =
  desktopRuntimeOptions(applicationId, maximumCacheSizeBytes, logger)

/** Desktop configuration for MapLibre Compose. */
public object MapLibre {
  /**
   * Sets the cache location and ambient cache budget for every map and offline operation in this
   * process.
   *
   * [applicationId] defaults to the package of the process `main` class. [maximumCacheSizeBytes]
   * defaults to MapLibre's cache budget. A conflicting call throws `IllegalStateException`.
   *
   * @param applicationId Reverse-domain name for the cache directory, such as `com.example.myapp`.
   * @param maximumCacheSizeBytes Ambient cache size in bytes, or null for MapLibre's default.
   */
  public fun configure(
    applicationId: String = inferredApplicationId(),
    maximumCacheSizeBytes: Long? = null,
    logger: Logger? = Logger.withTag("maplibre-compose"),
  ) {
    MlnFfiApplication.configure(desktopRuntimeOptions(applicationId, maximumCacheSizeBytes, logger))
  }
}

internal fun desktopRuntimeOptions(
  applicationId: String = inferredApplicationId(),
  maximumCacheSizeBytes: Long? = null,
  logger: Logger? = Logger.withTag("maplibre-compose"),
): MlnFfiRuntimeOptions {
  require(APPLICATION_ID.matches(applicationId)) {
    "applicationId must contain only nonempty dot-separated letters, digits, underscores, or hyphens"
  }
  val cachePath = desktopCachePath(applicationId)
  return MlnFfiRuntimeOptions(
    kotlinx.io.files.Path(cachePath.toString()),
    maximumCacheSizeBytes,
    logger,
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
