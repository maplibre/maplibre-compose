package dev.sargunv.maplibrecompose.demoapp

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import dev.sargunv.maplibrecompose.demoapp.demos.StyleSelectorDemo
import dev.sargunv.maplibrecompose.demoapp.design.CardColumn
import dev.sargunv.maplibrecompose.demoapp.design.Heading
import dev.sargunv.maplibrecompose.demoapp.design.PageColumn
import dev.sargunv.maplibrecompose.demoapp.design.SimpleListItem
import dev.sargunv.maplibrecompose.demoapp.util.Platform

@Composable
fun DemoSheetContent(state: DemoState, modifier: Modifier) {
  NavHost(
    navController = state.nav,
    startDestination = "MAIN_MENU",
    enterTransition = { slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Up) },
    exitTransition = { slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Up) },
    popEnterTransition = { slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Down) },
    popExitTransition = { slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Down) },
  ) {
    val allDemos = (listOf(StyleSelectorDemo) + Platform.extraDemos)

    composable("MAIN_MENU") {
      PageColumn(modifier) {
        Heading(text = "MapLibre Compose Demos")
        CardColumn {
          for (demo in allDemos) SimpleListItem(
            text = demo.name,
            onClick = { state.nav.navigate(demo.name) },
          )
        }
      }
    }

    for (demo in allDemos) {
      composable(demo.name) { demo.SheetContent(state, modifier) }
    }
  }
}
