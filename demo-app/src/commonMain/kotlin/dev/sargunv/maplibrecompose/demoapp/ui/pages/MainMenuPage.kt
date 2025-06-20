package dev.sargunv.maplibrecompose.demoapp.ui.pages

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.sargunv.maplibrecompose.demoapp.ui.design.CardColumn
import dev.sargunv.maplibrecompose.demoapp.ui.design.Heading
import dev.sargunv.maplibrecompose.demoapp.ui.design.PageColumn
import dev.sargunv.maplibrecompose.demoapp.ui.design.SimpleListItem
import dev.sargunv.maplibrecompose.demoapp.util.Platform

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainMenuPage(modifier: Modifier, onNavigate: (Any) -> Unit) {
  PageColumn(modifier = modifier) {
    Heading(text = "MapLibre Compose Demos")
    CardColumn {
      SimpleListItem(text = "Select a style", onClick = { onNavigate(Routes.StyleSelector) })
      Platform.extraRoutes.forEach { (route, title) ->
        SimpleListItem(text = title, onClick = { onNavigate(route) })
      }
    }
  }
}
