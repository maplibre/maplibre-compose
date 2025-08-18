package org.maplibre.kmp.native.map

import smjni.jnigen.CalledByNative
import smjni.jnigen.ExposeToNative

@ExposeToNative
public data class ResourceOptions(
  @get:CalledByNative val apiKey: String = "",
  @get:CalledByNative val tileServerOptions: TileServerOptions = TileServerOptions.DemoTiles,
  @get:CalledByNative val cachePath: String = "maplibre-cache",
  @get:CalledByNative val assetPath: String = "",
  @get:CalledByNative val maximumCacheSize: Long = 50 * 1024 * 1024, // 50 MB default
) {
  init {
    require(maximumCacheSize >= 0) { "maximumCacheSize must be non-negative" }
  }
}
