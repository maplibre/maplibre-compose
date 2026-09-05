package org.maplibre.compose.map

import kotlinx.browser.window

internal actual fun inputUptimeMillis(): Long = window.performance.now().toLong()
