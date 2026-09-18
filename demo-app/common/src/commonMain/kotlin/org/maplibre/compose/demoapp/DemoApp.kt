package org.maplibre.compose.demoapp

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.BottomSheetScaffoldState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.window.core.layout.WindowSizeClass
import kotlinx.coroutines.flow.filterNotNull
import org.maplibre.compose.demoapp.benchmark.BenchmarkMap
import org.maplibre.compose.demoapp.benchmark.BenchmarkRun
import org.maplibre.compose.demoapp.benchmark.benchmarkLaunchConfig

@Composable
fun DemoApp(contentPadding: PaddingValues = PaddingValues(0.dp)) {
  val benchmark = benchmarkLaunchConfig()
  if (benchmark != null) {
    BenchmarkRun(benchmark)
  } else {
    DemoApp(rememberDemoAppState(), contentPadding)
  }
}

@Composable
fun DemoApp(state: DemoAppState, contentPadding: PaddingValues = PaddingValues(0.dp)) {
  DemoAppTheme(state) { DemoShell(state, contentPadding) }
}

@Composable
fun DemoAppTheme(state: DemoAppState, content: @Composable () -> Unit) {
  val dark = if (state.shell == DemoShell.Benchmarks) true else state.appliedStyle.isDark
  val colorScheme = rememberDemoColorScheme(dark, state.settings.paletteMode)
  MaterialTheme(colorScheme = colorScheme, content = content)
}

private val MediumPanelWidth = 280.dp
private val ExpandedPanelWidth = 360.dp
private val ShellSpacing = 16.dp
private val SheetHandleHeight = 48.dp

private val MinimumUsefulSheetHeight = 320.dp

@Composable
private fun DemoShell(state: DemoAppState, contentPadding: PaddingValues) {
  val navController = rememberNavController()
  // One composition each for the map and the panel, so crossing the sidebar / bottom-sheet
  // breakpoint moves them: the map keeps its presentation and the NavHost keeps its back stack.
  val map = remember {
    movableContentOf { viewportInsets: () -> MapViewportInsets -> ShellMap(state, viewportInsets) }
  }
  val panel = remember {
    movableContentOf {
      modifier: Modifier,
      revealMap: suspend () -> Unit,
      peekSpacing: Dp,
      onPeekHeightChange: (Dp) -> Unit ->
      DemoPanel(state, navController, modifier, revealMap, peekSpacing, onPeekHeightChange)
    }
  }
  BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val safeInsets =
      WindowInsets.safeDrawing
        .toMapViewportInsets(density, layoutDirection)
        .union(contentPadding.toMapViewportInsets(layoutDirection))
    val windowSizeClass = currentWindowAdaptiveInfoV2().windowSizeClass
    if (windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND)) {
      SidebarLayout(safeInsets, sidebarWidth(maxWidth, safeInsets, windowSizeClass), map, panel)
    } else {
      SheetLayout(navController, safeInsets, map, panel)
    }
  }
}

/** Medium and wider windows: the panel is a sidebar beside the map. */
@Composable
private fun SidebarLayout(
  safeInsets: MapViewportInsets,
  targetPanelWidth: Dp,
  map: @Composable (() -> MapViewportInsets) -> Unit,
  panel: @Composable (Modifier, suspend () -> Unit, Dp, (Dp) -> Unit) -> Unit,
) {
  val layoutDirection = LocalLayoutDirection.current
  val panelWidth by
    animateDpAsState(
      targetPanelWidth,
      spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow),
      label = "demo panel width",
    )
  val viewportInsets =
    remember(safeInsets, layoutDirection) {
      { safeInsets.withLeadingPanel(panelWidth, layoutDirection) }
    }
  Box(Modifier.fillMaxSize()) {
    map(viewportInsets)
    Box(
      modifier =
        Modifier.align(Alignment.CenterStart)
          .fillMaxHeight()
          .padding(safeInsets.asPaddingValues())
          .consumeWindowInsets(safeInsets.asPaddingValues())
          .padding(ShellSpacing)
    ) {
      Surface(
        modifier = Modifier.width(panelWidth).fillMaxHeight(),
        shape = MaterialTheme.shapes.extraLarge,
        tonalElevation = 2.dp,
        shadowElevation = 8.dp,
      ) {
        panel(Modifier.fillMaxSize().padding(vertical = 8.dp), {}, 0.dp, {})
      }
    }
  }
}

/**
 * Compact windows: the panel is a bottom sheet. The sheet is expanded on the menu routes and peeks
 * while a demo is selected, showing the demo's title and primary control beside the map, so the map
 * is uncovered and back returns to the expanded menu.
 */
@Composable
private fun SheetLayout(
  navController: NavHostController,
  safeInsets: MapViewportInsets,
  map: @Composable (() -> MapViewportInsets) -> Unit,
  panel: @Composable (Modifier, suspend () -> Unit, Dp, (Dp) -> Unit) -> Unit,
) {
  BoxWithConstraints(Modifier.fillMaxSize()) {
    val density = LocalDensity.current
    val sheetState =
      rememberBottomSheetState(
        initialValue = SheetValue.Expanded,
        enabledValues = setOf(SheetValue.PartiallyExpanded, SheetValue.Expanded),
      )
    val scaffoldState = rememberBottomSheetScaffoldState(sheetState)
    val route = navController.currentBackStackEntryAsState().value?.destination?.route
    LaunchedEffect(route) { if (route != DemoRoute.Demo) sheetState.expand() }

    val sheetHeight = sheetHeight(maxHeight, safeInsets.top)
    // The demo route's report includes the bottom inset as spacing; other routes peek a title bar.
    var demoPeekHeight by remember { mutableStateOf(PanelHeaderHeight + safeInsets.bottom) }
    val peekContentHeight =
      if (route == DemoRoute.Demo) demoPeekHeight else PanelHeaderHeight + safeInsets.bottom
    val peekHeight = (SheetHandleHeight + peekContentHeight).coerceAtMost(sheetHeight)
    val visibleSheetHeight by
      rememberVisibleSheetHeight(
        scaffoldState = scaffoldState,
        initialHeight = peekHeight,
        viewportHeightPx = constraints.maxHeight,
        maximumHeightPx = with(density) { sheetHeight.roundToPx() },
        density = density,
      )
    val viewportInsets =
      remember(safeInsets) { { safeInsets.union(MapViewportInsets(bottom = visibleSheetHeight)) } }

    BottomSheetScaffold(
      scaffoldState = scaffoldState,
      sheetPeekHeight = peekHeight,
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
            .height(sheetHeight - SheetHandleHeight)
            // The shell keeps the sheet below the top safe area and pads the bottom one here.
            .consumeWindowInsets(WindowInsets.safeDrawing)
            .padding(bottom = safeInsets.bottom),
          { sheetState.partialExpand() },
          safeInsets.bottom,
          { demoPeekHeight = it },
        )
      },
    ) {
      map(viewportInsets)
    }
  }
}

@Composable
private fun rememberVisibleSheetHeight(
  scaffoldState: BottomSheetScaffoldState,
  initialHeight: Dp,
  viewportHeightPx: Int,
  maximumHeightPx: Int,
  density: Density,
): State<Dp> =
  produceState(
    initialValue = initialHeight,
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
        val visiblePx = (viewportHeightPx - offset).toInt().coerceIn(0, maximumHeightPx)
        value = with(density) { visiblePx.toDp() }
      }
  }

/** Half the window, at least [MinimumUsefulSheetHeight], and never into the top safe area. */
private fun sheetHeight(viewportHeight: Dp, topSafeInset: Dp): Dp =
  minOf(
    (viewportHeight - topSafeInset).coerceAtLeast(0.dp),
    maxOf(viewportHeight / 2, MinimumUsefulSheetHeight),
  )

private fun sidebarWidth(
  viewportWidth: Dp,
  safeInsets: MapViewportInsets,
  windowSizeClass: WindowSizeClass,
): Dp {
  val availableWidth =
    (viewportWidth - safeInsets.left - safeInsets.right - ShellSpacing * 2).coerceAtLeast(0.dp)
  val preferred =
    if (windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND))
      ExpandedPanelWidth
    else MediumPanelWidth
  return minOf(preferred, availableWidth)
}

private fun MapViewportInsets.withLeadingPanel(
  panelWidth: Dp,
  layoutDirection: LayoutDirection,
): MapViewportInsets {
  val panelOcclusion = ShellSpacing + panelWidth + ShellSpacing
  val panelEdge =
    when (layoutDirection) {
      LayoutDirection.Ltr -> MapViewportInsets(left = left + panelOcclusion)
      LayoutDirection.Rtl -> MapViewportInsets(right = right + panelOcclusion)
    }
  return union(panelEdge)
}

/** [viewportInsets] is read here, so inset changes recompose the map and not the shell. */
@Composable
private fun ShellMap(state: DemoAppState, viewportInsets: () -> MapViewportInsets) {
  if (state.shell == DemoShell.Benchmarks) {
    BenchmarkMap(state)
  } else {
    DemoMap(state, viewportInsets(), controls = demoMapControls(state.settings, state.location))
  }
}
