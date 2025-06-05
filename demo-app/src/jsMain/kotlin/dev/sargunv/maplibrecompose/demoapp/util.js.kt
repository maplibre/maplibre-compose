package dev.sargunv.maplibrecompose.demoapp

import dev.jordond.compass.geolocation.Geolocator
import dev.jordond.compass.geolocation.browser

actual fun getGeolocator() = Geolocator.browser()
