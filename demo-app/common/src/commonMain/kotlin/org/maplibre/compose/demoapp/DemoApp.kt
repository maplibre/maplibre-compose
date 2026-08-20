package org.maplibre.compose.demoapp

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.window.core.layout.WindowSizeClass
import org.maplibre.compose.demoapp.benchmark.BenchmarkMap

@Composable
fun DemoApp() {
  val state = rememberDemoAppState()
  val dark =
    if (state.shell == DemoShell.Benchmarks) state.selectedScenario.style.isDark
    else state.selectedStyle.isDark
  val colorScheme = if (dark) darkColorScheme() else lightColorScheme()
  // One composition for the panel, so the NavHost keeps its back stack when the
  // viewport crosses the side-pane / bottom-sheet breakpoint.
  val panel = remember { movableContentOf { modifier: Modifier -> DemoPanel(state, modifier) } }
  MaterialTheme(colorScheme = colorScheme) {
    val windowSizeClass = currentWindowAdaptiveInfo().windowSizeClass
    if (windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND)) {
      WideLayout(state, panel)
    } else {
      NarrowLayout(state, panel)
    }
  }
}

private val PanelWidth = 360.dp

/** How much of the map the collapsed sheet covers. */
private val SheetPeekHeight = 128.dp

@Composable
private fun WideLayout(state: DemoAppState, panel: @Composable (Modifier) -> Unit) {
  Row(Modifier.fillMaxSize()) {
    Surface(modifier = Modifier.width(PanelWidth).fillMaxHeight(), tonalElevation = 1.dp) {
      panel(Modifier.fillMaxHeight())
    }
    ShellMap(state, sheetInsets = WindowInsets(0))
  }
}

/** Height of the scaffold's default drag handle, which sits above the sheet content. */
private val SheetHandleHeight = 48.dp

@Composable
private fun NarrowLayout(state: DemoAppState, panel: @Composable (Modifier) -> Unit) {
  BoxWithConstraints(Modifier.fillMaxSize()) {
    val topInset = WindowInsets.safeDrawing.getTop(LocalDensity.current)
    // Expanded is the measured height of this content, capped so the sheet never enters
    // the top safe area. Only the map draws behind the notch.
    val maxSheetHeight =
      maxHeight - with(LocalDensity.current) { topInset.toDp() } - SheetHandleHeight
    BottomSheetScaffold(
      sheetPeekHeight = SheetPeekHeight,
      scaffoldState = rememberBottomSheetScaffoldState(),
      // NavHost SizeTransform interpolates the expanded height with the destination.
      sheetContent = {
        panel(
          Modifier.fillMaxWidth()
            .heightIn(max = maxSheetHeight)
            // Stop the panel's TopAppBar from padding itself for the top inset. The sheet
            // stays below the safe area, so that padding is just a bare forehead.
            .consumeWindowInsets(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
        )
      },
    ) {
      // The map draws under the scaffold content area, so the sheet covers its bottom edge.
      ShellMap(state, sheetInsets = WindowInsets(bottom = SheetPeekHeight))
    }
  }
}

@Composable
private fun ShellMap(state: DemoAppState, sheetInsets: WindowInsets) {
  if (state.shell == DemoShell.Benchmarks) {
    BenchmarkMap(state, sheetInsets)
  } else {
    DemoMap(state, sheetInsets)
  }
}
