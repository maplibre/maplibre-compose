package org.maplibre.compose.map

/** Monotonic dispatch timestamp for key input, whose Compose API exposes no event timestamp. */
internal expect fun inputUptimeMillis(): Long
