package dev.sargunv.maplibrecompose.demoapp

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import dev.sargunv.maplibrecompose.demoapp.demos.Demo
import dev.sargunv.maplibrecompose.demoapp.design.CardColumn
import dev.sargunv.maplibrecompose.demoapp.design.Heading
import dev.sargunv.maplibrecompose.demoapp.design.PageColumn
import dev.sargunv.maplibrecompose.demoapp.design.SimpleListItem

@Composable
fun DemoSheetContent(demos: List<Demo>, state: DemoState, modifier: Modifier) {
  NavHost(
    navController = state.nav,
    startDestination = "MAIN_MENU",
    enterTransition = { slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Up) },
    exitTransition = { slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Down) },
    popEnterTransition = { slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Up) },
    popExitTransition = { slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Down) },
  ) {
    composable("MAIN_MENU") {
      PageColumn(modifier) {
        Heading(text = "MapLibre Compose Demos")
        CardColumn {
          for (demo in demos) SimpleListItem(
            text = demo.name,
            onClick = { state.nav.navigate(demo.name) },
          )
        }
      }
    }

    for (demo in demos) {
      composable(demo.name) { demo.SheetContent(state, modifier) }
    }
  }
}
