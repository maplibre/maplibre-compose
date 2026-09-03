package org.maplibre.compose.sources

import android.net.Uri
import java.io.File
import org.maplibre.compose.mlnffi.AndroidContextProvider

private const val ASSET_PREFIX = "/android_asset/"

internal actual suspend fun localMbtilesPath(uri: String): String {
  val parsed = Uri.parse(uri)
  require(parsed.scheme == "file") { "MapLibre Native reads MBTiles from a file: URI, not '$uri'" }
  val path = requireNotNull(parsed.path) { "'$uri' has no path" }
  if (!path.startsWith(ASSET_PREFIX)) return File(path).absolutePath
  val context = AndroidContextProvider.context
  val assetPath = path.removePrefix(ASSET_PREFIX)
  // Assets change only with the package, so the installed APK identifies the copy.
  val apk = File(context.applicationInfo.sourceDir)
  val copy =
    copyPackagedFile(
      destination = File(context.cacheDir, "maplibre-compose/mbtiles/" + packagedCopyName(uri)),
      stamp = "${apk.length()}-${apk.lastModified()}",
      open = { context.assets.open(assetPath) },
    )
  return copy.absolutePath
}
