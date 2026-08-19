package org.maplibre.compose.resource

import java.io.FileNotFoundException
import java.net.URI
import java.net.URISyntaxException
import java.nio.file.NoSuchFileException
import org.maplibre.compose.mlnffi.AndroidMlnFfiPlatform

internal actual fun readPlatformResourceBytes(url: String): ByteArray =
  try {
    val uri = URI(url)
    val assetPrefix = "/android_asset/"
    val path = uri.path
    if (uri.scheme == "file" && path?.startsWith(assetPrefix) == true) {
      val assetPath = path.removePrefix(assetPrefix)
      AndroidMlnFfiPlatform.applicationContext.assets.open(assetPath).use { it.readBytes() }
    } else {
      uri.toURL().openStream().use { it.readBytes() }
    }
  } catch (error: Throwable) {
    if (error is VirtualMachineError) throw error
    throw MlnFfiResourceReadException(classify(error), error)
  }

private fun classify(error: Throwable): MlnFfiResourceReadFailure =
  when (error) {
    is FileNotFoundException,
    is NoSuchFileException -> MlnFfiResourceReadFailure.NOT_FOUND
    is URISyntaxException -> MlnFfiResourceReadFailure.INVALID_URL
    else -> MlnFfiResourceReadFailure.UNREADABLE
  }
