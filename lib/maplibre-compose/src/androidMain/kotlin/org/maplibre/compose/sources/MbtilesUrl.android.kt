package org.maplibre.compose.sources

import android.net.Uri
import java.io.File
import org.maplibre.compose.mlnffi.AndroidMlnFfiPlatform

private const val ASSET_PREFIX = "/android_asset/"

internal actual suspend fun localMbtilesPath(uri: String): String {
  val parsed = Uri.parse(uri)
  require(parsed.scheme == "file" && parsed.authority.isNullOrEmpty()) {
    "MapLibre Native reads MBTiles from a local file: URI, not '$uri'"
  }
  val path = requireNotNull(parsed.path) { "'$uri' has no path" }
  if (!path.startsWith(ASSET_PREFIX)) return File(path).absolutePath
  val context = AndroidMlnFfiPlatform.applicationContext
  val assetPath = path.removePrefix(ASSET_PREFIX)
  return copyPackagedFile(
      uri = uri,
      directory = File(context.cacheDir, "maplibre-compose/mbtiles"),
      open = { context.assets.open(assetPath) },
    )
    .absolutePath
}
