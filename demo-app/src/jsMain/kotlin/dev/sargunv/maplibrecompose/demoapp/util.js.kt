package dev.sargunv.maplibrecompose.demoapp

import dev.jordond.compass.geolocation.Geolocator
import dev.jordond.compass.geolocation.browser
import dev.sargunv.maplibrecompose.core.RenderOptions

actual fun getGeolocator() = Geolocator.browser()

actual fun RenderOptions.withMaxFps(maxFps: Int) = this
