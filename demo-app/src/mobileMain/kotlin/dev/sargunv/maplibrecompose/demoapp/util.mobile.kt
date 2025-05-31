package dev.sargunv.maplibrecompose.demoapp

import dev.jordond.compass.geolocation.Geolocator
import dev.jordond.compass.geolocation.mobile

actual fun getGeolocator() = Geolocator.mobile()
