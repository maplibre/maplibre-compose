package org.maplibre.compose.map

import kotlinx.browser.window

internal actual fun keyDispatchUptimeMillis(): Long = window.performance.now().toLong()
