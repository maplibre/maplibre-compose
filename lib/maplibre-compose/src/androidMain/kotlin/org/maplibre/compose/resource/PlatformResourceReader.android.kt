package org.maplibre.compose.resource

import java.net.URI
import org.maplibre.compose.mlnffi.AndroidMlnFfiPlatform

internal actual fun readPlatformResourceBytes(url: String): ByteArray {
  val uri = URI(url)
  val assetPrefix = "/android_asset/"
  val path = uri.path
  return if (uri.scheme == "file" && path?.startsWith(assetPrefix) == true) {
    val assetPath = path.removePrefix(assetPrefix)
    AndroidMlnFfiPlatform.applicationContext.assets.open(assetPath).use { it.readBytes() }
  } else {
    uri.toURL().openStream().use { it.readBytes() }
  }
}
