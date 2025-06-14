package dev.sargunv.maplibrecompose.demoapp

import androidx.compose.foundation.layout.PaddingValues
import dev.jordond.compass.geolocation.Geolocator
import dev.jordond.compass.geolocation.NotSupportedLocator
import dev.sargunv.maplibrecompose.core.OrnamentOptions
import dev.sargunv.maplibrecompose.core.RenderOptions

actual fun getGeolocator() = Geolocator(NotSupportedLocator)

actual fun RenderOptions.withMaxFps(maxFps: Int) = this

actual fun OrnamentOptions.withPadding(padding: PaddingValues) = this
