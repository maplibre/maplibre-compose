package org.maplibre.compose.desktop

/** Raised when a map's GPU bridge cannot be built or used. */
internal class DesktopHostException(message: String, cause: Throwable? = null) :
  RuntimeException(message, cause)
