package org.maplibre.compose.demoapp.demos

import androidx.compose.runtime.Composable
import kotlin.time.TimeMark
import org.maplibre.compose.location.LocationFix
import org.maplibre.compose.util.MaplibreComposable
import org.maplibre.spatialk.units.Bearing
import org.maplibre.spatialk.units.Rotation

actual val isNativeLocationIndicatorAvailable: Boolean = false

@Composable
@MaplibreComposable
actual fun NativeLocationIndicator(
  location: LocationFix?,
  measurementMark: TimeMark?,
  bearing: Bearing?,
  bearingAccuracy: Rotation?,
) {}
