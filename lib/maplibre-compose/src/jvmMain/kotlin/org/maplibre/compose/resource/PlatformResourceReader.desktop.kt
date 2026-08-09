package org.maplibre.compose.resource

import java.net.URI

internal actual fun readPlatformResourceBytes(url: String): ByteArray =
  URI(url).toURL().openStream().use { it.readBytes() }
