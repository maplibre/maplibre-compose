package org.maplibre.compose.map

import kotlinx.io.files.Path
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask

/** `maplibre-cache.db` in the application's caches directory. */
internal actual fun defaultCacheFile(): Path {
  val caches =
    NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, expandTilde = true)
      .first() as String
  return Path(caches, "maplibre-cache.db")
}

internal actual fun initializeNativePlatform() {}
