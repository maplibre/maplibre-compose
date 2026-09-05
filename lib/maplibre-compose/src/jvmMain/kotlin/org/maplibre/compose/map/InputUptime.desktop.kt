package org.maplibre.compose.map

internal actual fun inputUptimeMillis(): Long = System.nanoTime() / 1_000_000
