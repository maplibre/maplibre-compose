package org.maplibre.compose.sources

internal actual suspend fun localMbtilesPath(uri: String): String =
  throw UnsupportedOperationException("MapLibre GL JS does not read MBTiles files")
