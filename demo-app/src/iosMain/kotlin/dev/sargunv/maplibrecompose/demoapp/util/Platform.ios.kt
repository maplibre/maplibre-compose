package dev.sargunv.maplibrecompose.demoapp.util

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import dev.sargunv.maplibrecompose.demoapp.ui.pages.OfflineManagerPage
import dev.sargunv.maplibrecompose.demoapp.ui.pages.Routes
import platform.UIKit.UIDevice

actual object Platform {
  actual val name = "iOS"

  actual val version = UIDevice.currentDevice.systemVersion

  actual val supportedFeatures = PlatformFeature.Everything

  actual val extraRoutes: Map<Any, String> = mapOf(Routes.OfflineManager to "Offline Manager")

  actual fun NavGraphBuilder.extraNavGraph(ctx: Routes.Context) {
    composable<Routes.OfflineManager> {
      OfflineManagerPage(
        modifier = ctx.modifier,
        onNavigateBack = { ctx.nav.popBackStack() },
        cameraState = ctx.cameraState,
        selectedStyle = ctx.selectedStyle,
      )
    }
  }
}
