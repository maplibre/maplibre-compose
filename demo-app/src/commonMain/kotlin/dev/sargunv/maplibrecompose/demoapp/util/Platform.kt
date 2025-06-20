package dev.sargunv.maplibrecompose.demoapp.util

import androidx.navigation.NavGraphBuilder
import dev.sargunv.maplibrecompose.demoapp.ui.pages.Routes

expect object Platform {
  val name: String

  val version: String

  val supportedFeatures: Set<PlatformFeature>

  val extraRoutes: Map<Any, String>

  fun NavGraphBuilder.extraNavGraph(ctx: Routes.Context)
}

enum class PlatformFeature {
  InteropBlending,
  LayerStyling;

  companion object {
    val Everything = entries.toSet()
  }
}
