package org.maplibre.compose.map

import android.os.SystemClock

// Android input timestamps use monotonic uptime, which excludes deep sleep.
internal actual fun keyDispatchUptimeMillis(): Long = SystemClock.uptimeMillis()
