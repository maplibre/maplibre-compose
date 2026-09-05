package org.maplibre.compose.map

import android.os.SystemClock

internal actual fun inputUptimeMillis(): Long = SystemClock.uptimeMillis()
