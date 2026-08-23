package org.maplibre.compose.demoapp

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.BottomSheetScaffoldState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.window.core.layout.WindowSizeClass
import kotlinx.coroutines.flow.filterNotNull
import org.maplibre.compose.demoapp.benchmark.BenchmarkMap

@Composable
fun DemoApp() {
  val state = rememberDemoAppState()
  val dark =
    if (state.shell == DemoShell.Benchmarks) state.selectedScenario.style.isDark
    else state.selectedStyle.isDark
  val colorScheme = if (dark) darkColorScheme() else lightColorScheme()
  val sheetState = rememberBottomSheetScaffoldState()
  // One composition for the panel, so the NavHost keeps its back stack when the viewport crosses
  // the floating-panel / bottom-sheet breakpoint.
  val panel = remember {
    movableContentOf { modifier: Modifier, revealMap: suspend () -> Unit ->
      DemoPanel(state, modifier, revealMap)
    }
  }
  MaterialTheme(colorScheme = colorScheme) {
    val windowSizeClass = currentWindowAdaptiveInfo().windowSizeClass
    if (windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND)) {
      WideLayout(state, panel)
    } else {
      NarrowLayout(state, sheetState, panel)
    }
  }
}

private val PanelWidth = 360.dp
private val ShellSpacing = 16.dp
private val SheetPeekHeight = 128.dp
private val SheetHandleHeight = 48.dp
private val MinimumUsefulSheetHeight = 320.dp

@Composable
private fun WideLayout(
  state: DemoAppState,
  panel: @Composable (Modifier, suspend () -> Unit) -> Unit,
) {
  val density = LocalDensity.current
  val layoutDirection = LocalLayoutDirection.current
  val safeInsets = WindowInsets.safeDrawing.toMapViewportInsets(density, layoutDirection)
  val panelEdge =
    when (layoutDirection) {
      LayoutDirection.Ltr ->
        MapViewportInsets(left = safeInsets.left + ShellSpacing + PanelWidth + ShellSpacing)
      LayoutDirection.Rtl ->
        MapViewportInsets(right = safeInsets.right + ShellSpacing + PanelWidth + ShellSpacing)
    }
  val viewportInsets = safeInsets.union(panelEdge)

  Box(Modifier.fillMaxSize()) {
    ShellMap(state, viewportInsets)
    Box(
      modifier =
        Modifier.align(Alignment.CenterStart)
          .fillMaxHeight()
          .padding(safeInsets.asPaddingValues())
          .padding(ShellSpacing)
    ) {
      Surface(
        modifier = Modifier.width(PanelWidth).fillMaxHeight(),
        shape = MaterialTheme.shapes.extraLarge,
        tonalElevation = 2.dp,
        shadowElevation = 8.dp,
      ) {
        panel(
          Modifier.fillMaxHeight().consumeWindowInsets(WindowInsets.safeDrawing),
          {},
        )
      }
    }
  }
}

@Composable
private fun NarrowLayout(
  state: DemoAppState,
  scaffoldState: BottomSheetScaffoldState,
  panel: @Composable (Modifier, suspend () -> Unit) -> Unit,
) {
  BoxWithConstraints(Modifier.fillMaxSize()) {
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val safeInsets = WindowInsets.safeDrawing.toMapViewportInsets(density, layoutDirection)
    val maxSheetHeight =
      maximumSheetHeight(
        viewportHeight = maxHeight,
        topSafeInset = safeInsets.top,
        minimumUsefulHeight = MinimumUsefulSheetHeight,
      )
    val sheetHeight by
      rememberVisibleSheetHeight(
        scaffoldState = scaffoldState,
        viewportHeightPx = constraints.maxHeight,
        maximumHeightPx = with(density) { maxSheetHeight.roundToPx() },
        density = density,
      )
    val viewportInsets = safeInsets.union(MapViewportInsets(bottom = sheetHeight))

    BottomSheetScaffold(
      sheetPeekHeight = SheetPeekHeight,
      scaffoldState = scaffoldState,
      sheetDragHandle = {
        Box(
          modifier = Modifier.fillMaxWidth().height(SheetHandleHeight),
          contentAlignment = Alignment.Center,
        ) {
          BottomSheetDefaults.DragHandle()
        }
      },
      sheetContent = {
        panel(
          Modifier.fillMaxWidth()
            .heightIn(max = (maxSheetHeight - SheetHandleHeight).coerceAtLeast(0.dp))
            // The sheet already stays below the top safe area.
            .consumeWindowInsets(WindowInsets.safeDrawing.only(WindowInsetsSides.Top)),
          {
            val sheet = scaffoldState.bottomSheetState
            if (sheet.hasPartiallyExpandedState) sheet.partialExpand()
          },
        )
      },
    ) {
      ShellMap(state, viewportInsets)
    }
  }
}

@Composable
private fun rememberVisibleSheetHeight(
  scaffoldState: BottomSheetScaffoldState,
  viewportHeightPx: Int,
  maximumHeightPx: Int,
  density: Density,
): State<Dp> =
  produceState(
    initialValue = SheetPeekHeight,
    scaffoldState,
    viewportHeightPx,
    maximumHeightPx,
    density,
  ) {
    snapshotFlow {
      runCatching { scaffoldState.bottomSheetState.requireOffset() }.getOrNull()
    }
      .filterNotNull()
      .collect { offset ->
        val visiblePx = visibleSheetHeight(viewportHeightPx, offset).coerceAtMost(maximumHeightPx)
        value = with(density) { visiblePx.toDp() }
      }
  }

private fun maximumSheetHeight(
  viewportHeight: Dp,
  topSafeInset: Dp,
  minimumUsefulHeight: Dp,
): Dp =
  minOf(
    (viewportHeight - topSafeInset).coerceAtLeast(0.dp),
    maxOf(viewportHeight / 2, minimumUsefulHeight),
  )

private fun visibleSheetHeight(viewportHeightPx: Int, sheetOffsetPx: Float): Int =
  (viewportHeightPx - sheetOffsetPx).toInt().coerceIn(0, viewportHeightPx)

@Composable
private fun ShellMap(state: DemoAppState, viewportInsets: MapViewportInsets) {
  if (state.shell == DemoShell.Benchmarks) {
    BenchmarkMap(state, viewportInsets)
  } else {
    DemoMap(state, viewportInsets)
  }
}
