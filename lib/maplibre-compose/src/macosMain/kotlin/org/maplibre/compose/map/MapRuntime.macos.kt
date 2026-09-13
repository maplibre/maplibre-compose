package org.maplibre.compose.map

import kotlinx.io.files.Path
import platform.Foundation.NSBundle
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask

internal actual fun defaultCacheFile(): Path {
  val caches =
    NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, true).first() as String
  val application = NSBundle.mainBundle.bundleIdentifier ?: NSProcessInfo.processInfo.processName
  return Path(caches, application, "maplibre-cache.db")
}

internal actual fun initializeNativePlatform() {}
