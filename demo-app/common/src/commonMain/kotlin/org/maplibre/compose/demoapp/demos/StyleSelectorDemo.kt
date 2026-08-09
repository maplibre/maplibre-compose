package org.maplibre.compose.demoapp.demos

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.maplibre.compose.demoapp.DemoState
import org.maplibre.compose.demoapp.OpenFreeMap
import org.maplibre.compose.demoapp.OtherStyles
import org.maplibre.compose.demoapp.Protomaps
import org.maplibre.compose.demoapp.Versatiles
import org.maplibre.compose.demoapp.design.CardColumn
import org.maplibre.compose.demoapp.design.SelectableListItem
import org.maplibre.compose.demoapp.design.Subheading

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
