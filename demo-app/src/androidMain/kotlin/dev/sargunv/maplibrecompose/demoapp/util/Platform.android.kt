package dev.sargunv.maplibrecompose.demoapp.util

import android.os.Build
import dev.sargunv.maplibrecompose.demoapp.demos.Demo
import dev.sargunv.maplibrecompose.demoapp.demos.OfflineManagerDemo
import dev.sargunv.maplibrecompose.demoapp.demos.RenderOptionsDemo

actual object Platform {
  actual val name = "Android"

  actual val version = "${Build.VERSION.RELEASE} ${Build.VERSION.CODENAME}"

  actual val supportedFeatures = PlatformFeature.Everything

  actual val extraDemos: List<Demo> = listOf(OfflineManagerDemo, RenderOptionsDemo)
}
