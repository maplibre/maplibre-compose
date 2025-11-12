package org.maplibre.compose.demoapp.util

import android.os.Build
import androidx.compose.foundation.layout.PaddingValues
import org.maplibre.compose.demoapp.demos.Demo
import org.maplibre.compose.demoapp.demos.GestureOptionsDemo
import org.maplibre.compose.demoapp.demos.GmsLocationDemo
import org.maplibre.compose.demoapp.demos.OfflineManagerDemo
import org.maplibre.compose.demoapp.demos.OrnamentOptionsDemo
import org.maplibre.compose.demoapp.demos.RenderOptionsDemo
import org.maplibre.compose.map.OrnamentOptions

actual object Platform {
  actual val name = "Android"

  actual val version = "${Build.VERSION.RELEASE} ${Build.VERSION.CODENAME}"

  actual val supportedFeatures = PlatformFeature.Everything

  actual val extraDemos: List<Demo> =
    listOf(
      GestureOptionsDemo,
      OrnamentOptionsDemo,
      OfflineManagerDemo,
      RenderOptionsDemo,
      GmsLocationDemo,
    )

  actual fun padOrnaments(options: OrnamentOptions, padding: PaddingValues) =
    options.copy(padding = padding)
}
