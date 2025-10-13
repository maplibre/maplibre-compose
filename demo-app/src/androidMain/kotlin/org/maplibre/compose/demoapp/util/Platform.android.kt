package org.maplibre.compose.demoapp.util

import android.os.Build
import org.maplibre.compose.demoapp.demos.Demo
import org.maplibre.compose.demoapp.demos.GmsLocationDemo
import org.maplibre.compose.demoapp.demos.OfflineManagerDemo
import org.maplibre.compose.demoapp.demos.RenderOptionsDemo

actual object Platform {
  actual val name = "Android"

  actual val version = "${Build.VERSION.RELEASE} ${Build.VERSION.CODENAME}"

  actual val supportedFeatures = PlatformFeature.Everything

  actual val extraDemos: List<Demo> = listOf(OfflineManagerDemo, RenderOptionsDemo, GmsLocationDemo)
}
