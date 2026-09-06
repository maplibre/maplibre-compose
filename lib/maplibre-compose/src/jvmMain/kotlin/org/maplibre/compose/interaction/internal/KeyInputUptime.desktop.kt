package org.maplibre.compose.interaction.internal

internal actual fun keyDispatchUptimeMillis(): Long = System.nanoTime() / 1_000_000
