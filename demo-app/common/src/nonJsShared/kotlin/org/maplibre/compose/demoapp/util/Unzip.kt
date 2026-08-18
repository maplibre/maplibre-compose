package org.maplibre.compose.demoapp.util

/** Extracts a ZIP archive into a map of entry name to contents, skipping directories. */
internal expect fun unzip(bytes: ByteArray): Map<String, ByteArray>
