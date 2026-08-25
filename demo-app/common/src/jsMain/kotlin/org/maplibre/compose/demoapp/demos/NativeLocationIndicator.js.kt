package org.maplibre.compose.demoapp.demos

import androidx.compose.runtime.Composable
import org.maplibre.compose.location.BearingWithAccuracy
import org.maplibre.compose.location.Location
import org.maplibre.compose.util.MaplibreComposable

actual val isNativeLocationIndicatorAvailable: Boolean = false

@Composable
@MaplibreComposable
actual fun NativeLocationIndicator(location: Location?, bearing: BearingWithAccuracy?) {}
