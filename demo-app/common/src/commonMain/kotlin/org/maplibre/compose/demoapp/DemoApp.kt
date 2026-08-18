package org.maplibre.compose.demoapp

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun DemoApp() {
  val state = rememberDemoAppState()
  val colorScheme = if (state.selectedStyle.isDark) darkColorScheme() else lightColorScheme()
  // One composition for the panel, so the NavHost keeps its back stack when the
  // viewport crosses the side-pane / bottom-sheet breakpoint.
  val panel = remember { movableContentOf { modifier: Modifier -> DemoPanel(state, modifier) } }
  MaterialTheme(colorScheme = colorScheme) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
      if (maxWidth >= WideLayoutMinWidth) {
        WideLayout(state, panel)
      } else {
        NarrowLayout(state, panel)
      }
    }
  }
}

/** Below this width the panel becomes a bottom sheet. */
private val WideLayoutMinWidth = 720.dp

private val PanelWidth = 360.dp

/** How much of the map the collapsed sheet covers. */
private val SheetPeekHeight = 128.dp

@Composable
private fun WideLayout(state: DemoAppState, panel: @Composable (Modifier) -> Unit) {
  Row(Modifier.fillMaxSize()) {
    Surface(modifier = Modifier.width(PanelWidth).fillMaxHeight(), tonalElevation = 1.dp) {
      panel(Modifier.fillMaxHeight())
    }
    DemoMap(state, sheetInsets = WindowInsets(0))
  }
}

@Composable
private fun NarrowLayout(state: DemoAppState, panel: @Composable (Modifier) -> Unit) {
  BottomSheetScaffold(
    sheetPeekHeight = SheetPeekHeight,
    scaffoldState = rememberBottomSheetScaffoldState(),
    sheetContent = { panel(Modifier) },
  ) {
    // The map draws under the scaffold content area, so the sheet covers its bottom edge.
    DemoMap(state, sheetInsets = WindowInsets(bottom = SheetPeekHeight))
  }
}
