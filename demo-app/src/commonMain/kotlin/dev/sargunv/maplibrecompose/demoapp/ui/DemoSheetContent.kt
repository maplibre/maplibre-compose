package dev.sargunv.maplibrecompose.demoapp.ui

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import dev.sargunv.maplibrecompose.demoapp.ui.pages.MainMenuPage
import dev.sargunv.maplibrecompose.demoapp.ui.pages.Routes
import dev.sargunv.maplibrecompose.demoapp.ui.pages.StyleSelectorPage
import dev.sargunv.maplibrecompose.demoapp.util.Platform

@Composable
fun DemoSheetContent(ctx: Routes.Context) {
  NavHost(
    navController = ctx.nav,
    startDestination = Routes.MainMenu,
    enterTransition = { slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Up) },
    exitTransition = { slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Up) },
    popEnterTransition = { slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Down) },
    popExitTransition = { slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Down) },
  ) {
    composable<Routes.MainMenu> {
      MainMenuPage(modifier = ctx.modifier, onNavigate = { ctx.nav.navigate(it) })
    }

    composable<Routes.StyleSelector> {
      StyleSelectorPage(
        ctx.modifier,
        onNavigateBack = { ctx.nav.popBackStack() },
        selectedStyle = ctx.selectedStyle,
        onStyleSelected = ctx.onStyleSelected,
      )
    }

    with(Platform) { extraNavGraph(ctx) }
  }
}
