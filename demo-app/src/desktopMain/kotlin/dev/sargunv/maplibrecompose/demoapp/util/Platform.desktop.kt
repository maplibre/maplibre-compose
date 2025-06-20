package dev.sargunv.maplibrecompose.demoapp.util

import androidx.navigation.NavGraphBuilder
import dev.sargunv.maplibrecompose.demoapp.ui.pages.Routes

actual object Platform {
  actual val name = System.getProperty("os.name")!!

  actual val version = System.getProperty("os.version")!!

  actual val supportedFeatures = emptySet<PlatformFeature>()

  actual val extraRoutes: Map<Any, String> = emptyMap()

  actual fun NavGraphBuilder.extraNavGraph(ctx: Routes.Context) {}
}
