package dev.sargunv.maplibrecompose.demoapp

import androidx.compose.foundation.layout.PaddingValues
import dev.jordond.compass.geolocation.Geolocator
import dev.jordond.compass.geolocation.browser
import dev.sargunv.maplibrecompose.core.OrnamentOptions
import dev.sargunv.maplibrecompose.core.RenderOptions

actual fun getGeolocator() = Geolocator.browser()

actual fun RenderOptions.withMaxFps(maxFps: Int) = this

actual fun OrnamentOptions.withPadding(padding: PaddingValues) = this
