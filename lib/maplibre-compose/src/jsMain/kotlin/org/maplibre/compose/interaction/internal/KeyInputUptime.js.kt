package org.maplibre.compose.interaction.internal

import kotlinx.browser.window

internal actual fun keyDispatchUptimeMillis(): Long = window.performance.now().toLong()
