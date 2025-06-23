package dev.sargunv.maplibrecompose.demoapp.demos

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.sargunv.maplibrecompose.demoapp.DemoState
import dev.sargunv.maplibrecompose.demoapp.OpenFreeMap
import dev.sargunv.maplibrecompose.demoapp.OtherStyles
import dev.sargunv.maplibrecompose.demoapp.Protomaps
import dev.sargunv.maplibrecompose.demoapp.Versatiles
import dev.sargunv.maplibrecompose.demoapp.design.CardColumn
import dev.sargunv.maplibrecompose.demoapp.design.SelectableListItem
import dev.sargunv.maplibrecompose.demoapp.design.Subheading

object StyleSelectorDemo : Demo {
  override val name = "Select a style"

  @Composable
  override fun SheetContent(state: DemoState, modifier: Modifier) {

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
          SelectableListItem(
            text = style.displayName,
            onClick = { state.selectedStyle = style },
            isSelected = style == state.selectedStyle,
          )
        }
      }
    }
  }
}
