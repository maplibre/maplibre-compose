package org.maplibre.compose.ios

import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask

/** Returns the default private cache database for this application. */
public fun iosCacheFile(): String {
  val caches =
    NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, expandTilde = true)
      .first() as String
  return "$caches/maplibre-cache.db"
}
