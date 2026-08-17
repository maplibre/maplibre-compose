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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun DemoApp() {
  val state = rememberDemoAppState()
  val colorScheme = if (state.selectedStyle.isDark) darkColorScheme() else lightColorScheme()
  MaterialTheme(colorScheme = colorScheme) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
      if (maxWidth >= WideLayoutMinWidth) {
        WideLayout(state)
      } else {
        NarrowLayout(state)
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
private fun WideLayout(state: DemoAppState) {
  Row(Modifier.fillMaxSize()) {
    Surface(modifier = Modifier.width(PanelWidth).fillMaxHeight(), tonalElevation = 1.dp) {
      DemoPanel(state, modifier = Modifier.fillMaxHeight())
    }
    DemoMap(state, sheetInsets = WindowInsets(0))
  }
}

@Composable
private fun NarrowLayout(state: DemoAppState) {
  BottomSheetScaffold(
    sheetPeekHeight = SheetPeekHeight,
    scaffoldState = rememberBottomSheetScaffoldState(),
    sheetContent = { DemoPanel(state) },
  ) {
    // The map draws under the scaffold content area, so the sheet covers its bottom edge.
    DemoMap(state, sheetInsets = WindowInsets(bottom = SheetPeekHeight))
  }
}
