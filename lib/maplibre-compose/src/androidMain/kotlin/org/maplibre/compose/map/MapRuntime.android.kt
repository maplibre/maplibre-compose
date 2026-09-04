package org.maplibre.compose.map

import kotlinx.io.files.Path
import org.maplibre.compose.mlnffi.AndroidMlnFfiPlatform

internal actual fun defaultCacheFile(): Path =
  Path(AndroidMlnFfiPlatform.applicationContext.cacheDir.resolve("maplibre-cache.db").absolutePath)

internal actual fun initializeNativePlatform() {
  AndroidMlnFfiPlatform.initialize()
}
