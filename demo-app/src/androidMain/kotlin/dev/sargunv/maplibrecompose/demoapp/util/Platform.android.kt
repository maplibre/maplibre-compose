package dev.sargunv.maplibrecompose.demoapp.util

import android.os.Build
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import dev.sargunv.maplibrecompose.demoapp.ui.pages.OfflineManagerPage
import dev.sargunv.maplibrecompose.demoapp.ui.pages.Routes

actual object Platform {
  actual val name = "Android"

  actual val version = "${Build.VERSION.RELEASE} ${Build.VERSION.CODENAME}"

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
