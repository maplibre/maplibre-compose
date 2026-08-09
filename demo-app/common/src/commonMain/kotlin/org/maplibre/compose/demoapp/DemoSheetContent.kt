package org.maplibre.compose.demoapp

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.vectorResource
import org.maplibre.compose.demoapp.demos.Demo
import org.maplibre.compose.demoapp.design.CardColumn
import org.maplibre.compose.demoapp.design.CloseButton
import org.maplibre.compose.demoapp.design.Heading
import org.maplibre.compose.demoapp.design.PageColumn
import org.maplibre.compose.demoapp.design.SelectableListItem
import org.maplibre.compose.demoapp.generated.Res
import org.maplibre.compose.demoapp.generated.filter_center_focus_24px
import org.maplibre.compose.demoapp.generated.visibility_24px
import org.maplibre.compose.demoapp.generated.visibility_off_24px

@Composable
fun DemoSheetContent(state: DemoState, modifier: Modifier) {
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
        CardColumn { for (demo in state.demos) DemoListItem(demo, state) }
      }
    }

    for (demo in state.demos) {
      composable(demo.name) {
        PageColumn(modifier = modifier) {
          Heading(text = demo.name, trailingContent = { CloseButton { state.nav.popBackStack() } })
          demo.SheetContent(state, modifier)
        }
      }
    }
  }
}

@Composable
private fun DemoListItem(demo: Demo, state: DemoState) {
  val coroutineScope = rememberCoroutineScope()
  SelectableListItem(
    text = demo.name,
    onClick = { state.nav.navigate(demo.name) },
    trailingContent = {
      Row {
        demo.region?.let { region ->
          // TODO padding based on bottom sheet?
          FlyToRegionButton(
            onClick = {
              coroutineScope.launch {
                state.cameraState.animateTo(boundingBox = region, padding = PaddingValues(32.dp))
              }
            }
          )
        }

        demo.mapContentVisibilityState?.let { visibilityState ->
          HideShowButton(
            isVisible = visibilityState.value,
            onClick = { visibilityState.value = !visibilityState.value },
          )
        }
      }
    },
  )
}

@Composable
private fun FlyToRegionButton(onClick: () -> Unit) {
  IconButton(onClick = onClick) {
    Icon(
      vectorResource(Res.drawable.filter_center_focus_24px),
      contentDescription = "Fly to region",
    )
  }
}

@Composable
fun HideShowButton(isVisible: Boolean, onClick: () -> Unit) {
  IconButton(onClick = onClick) {
    AnimatedContent(isVisible) { isVisible ->
      Icon(
        imageVector =
          vectorResource(
            if (isVisible) Res.drawable.visibility_24px else Res.drawable.visibility_off_24px
          ),
        contentDescription = if (isVisible) "Hide" else "Show",
      )
    }
  }
}
