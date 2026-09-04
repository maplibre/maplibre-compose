package org.maplibre.compose.map

import kotlinx.io.files.Path
import org.maplibre.compose.desktop.desktopCachePath
import org.maplibre.compose.desktop.inferredApplicationId

/** `maplibre-cache.db` in a per-user cache directory named after the process main class package. */
internal actual fun defaultCacheFile(): Path =
  Path(desktopCachePath(inferredApplicationId()).toString())

internal actual fun initializeNativePlatform() {}
