package dev.sargunv.maplibrecompose.demoapp.util

import androidx.navigation.NavGraphBuilder
import dev.sargunv.maplibrecompose.demoapp.ui.pages.Routes
import kotlinx.browser.window

actual object Platform {
  actual val name = "JS on ${window.navigator.appName}"

  actual val version = window.navigator.appVersion

  actual val supportedFeatures = emptySet<PlatformFeature>()

  actual val extraRoutes: Map<Any, String> = emptyMap()

  actual fun NavGraphBuilder.extraNavGraph(ctx: Routes.Context) {}
}
