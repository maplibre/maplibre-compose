package dev.sargunv.maplibrecompose.demoapp

import dev.jordond.compass.geolocation.Geolocator
import dev.jordond.compass.geolocation.NotSupportedLocator
import dev.sargunv.maplibrecompose.core.RenderOptions

actual fun getGeolocator() = Geolocator(NotSupportedLocator)

actual fun RenderOptions.withMaxFps(maxFps: Int) = this
