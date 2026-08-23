package org.maplibre.compose.demoapp.util

/** Extracts a ZIP archive into a map from entry names to contents, skipping directories. */
internal expect suspend fun unzip(bytes: ByteArray): Map<String, ByteArray>
