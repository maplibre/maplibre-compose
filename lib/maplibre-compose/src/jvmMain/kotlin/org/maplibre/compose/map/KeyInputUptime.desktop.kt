package org.maplibre.compose.map

internal actual fun keyDispatchUptimeMillis(): Long = System.nanoTime() / 1_000_000
