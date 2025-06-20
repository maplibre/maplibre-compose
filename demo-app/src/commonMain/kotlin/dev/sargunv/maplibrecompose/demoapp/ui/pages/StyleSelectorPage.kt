package dev.sargunv.maplibrecompose.demoapp.ui.pages

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.sargunv.maplibrecompose.demoapp.OpenFreeMap
import dev.sargunv.maplibrecompose.demoapp.OtherStyles
import dev.sargunv.maplibrecompose.demoapp.Protomaps
import dev.sargunv.maplibrecompose.demoapp.StyleInfo
import dev.sargunv.maplibrecompose.demoapp.Versatiles
import dev.sargunv.maplibrecompose.demoapp.ui.design.BackButton
import dev.sargunv.maplibrecompose.demoapp.ui.design.CardColumn
import dev.sargunv.maplibrecompose.demoapp.ui.design.Heading
import dev.sargunv.maplibrecompose.demoapp.ui.design.PageColumn
import dev.sargunv.maplibrecompose.demoapp.ui.design.SimpleListItem
import dev.sargunv.maplibrecompose.demoapp.ui.design.Subheading

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StyleSelectorPage(
  modifier: Modifier,
  onNavigateBack: () -> Unit,
  selectedStyle: StyleInfo,
  onStyleSelected: (StyleInfo) -> Unit,
) {
  PageColumn(modifier = modifier) {
    Heading(text = "Demo Styles", trailingContent = { BackButton { onNavigateBack() } })

    val stylesByProvider =
      mapOf(
        "Protomaps" to Protomaps.entries,
        "OpenFreeMap" to OpenFreeMap.entries,
        "Versatiles" to Versatiles.entries,
        "Other Styles" to OtherStyles.entries,
      )

    stylesByProvider.forEach { (provider, styles) ->
      Subheading(text = provider)
      CardColumn {
        styles.forEach { style ->
          SimpleListItem(
            text = style.displayName,
            onClick = { onStyleSelected(style) },
            isSelected = style == selectedStyle,
          )
        }
      }
    }
  }
}
