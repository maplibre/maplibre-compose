package org.maplibre.compose.interaction.internal

/**
 * Monotonic dispatch timestamp for keyboard callbacks. Compose's common KeyEvent API exposes no
 * event timestamp, so this records when the map receives the key event.
 */
internal expect fun keyDispatchUptimeMillis(): Long
