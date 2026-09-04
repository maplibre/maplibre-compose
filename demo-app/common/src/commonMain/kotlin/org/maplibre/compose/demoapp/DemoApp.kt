package org.maplibre.compose.demoapp

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.window.core.layout.WindowSizeClass
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.vectorResource
import org.maplibre.compose.demoapp.benchmark.BenchmarkMap
import org.maplibre.compose.demoapp.generated.Res
import org.maplibre.compose.demoapp.generated.chevron_left_24px
import org.maplibre.compose.demoapp.generated.chevron_right_24px

@Composable
fun DemoApp(
  state: DemoAppState = rememberDemoAppState(),
  contentPadding: PaddingValues = PaddingValues(0.dp),
) {
  DemoAppTheme(state) { DemoShell(state, contentPadding) }
}

@Composable
fun DemoAppTheme(state: DemoAppState, content: @Composable () -> Unit) {
  val dark =
    if (state.shell == DemoShell.Benchmarks) state.selectedScenario.style.isDark
    else state.appliedStyle.isDark
  val colorScheme = rememberDemoColorScheme(dark, state.settings.paletteMode)
  MaterialTheme(colorScheme = colorScheme, content = content)
}

private val MediumPanelWidth = 280.dp
private val ExpandedPanelWidth = 360.dp
private val ShellSpacing = 16.dp
private val HandleWidth = 28.dp
private val HandleHeight = 56.dp

/** How far the handle tucks under the panel, so their shadows merge into one attached surface. */
private val HandleOverlap = 6.dp
private val HandleProtrusion = HandleWidth - HandleOverlap
private const val PanelMotionDurationMillis = 220
private val PanelMotionEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

private enum class DemoShellLayout {
  Compact,
  Medium,
  Expanded,
}

@Composable
private fun DemoShell(state: DemoAppState, contentPadding: PaddingValues) {
  BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val safeInsets =
      WindowInsets.safeDrawing
        .toMapViewportInsets(density, layoutDirection)
        .union(contentPadding.toMapViewportInsets(layoutDirection))
    val windowAdaptiveInfo = currentWindowAdaptiveInfoV2()
    val windowSizeClass = windowAdaptiveInfo.windowSizeClass
    val layout = windowSizeClass.toDemoShellLayout()
    val layoutTransition = updateTransition(layout, label = "demo shell layout")
    val panelWidth by
      layoutTransition.animateDp(
        transitionSpec = {
          spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow,
          )
        },
        label = "demo panel width",
      ) { targetLayout ->
        resolvedPanelWidth(maxWidth, safeInsets, targetLayout)
      }
    var panelOpen by rememberSaveable { mutableStateOf(true) }
    val panelProgress = remember { Animatable(if (panelOpen) 1f else 0f) }
    val scope = rememberCoroutineScope()
    val setPanelOpen: suspend (Boolean) -> Unit =
      remember(panelProgress) {
        { open ->
          if (open) panelOpen = true
          panelProgress.animateTo(
            targetValue = if (open) 1f else 0f,
            animationSpec = tween(PanelMotionDurationMillis, easing = PanelMotionEasing),
          )
          if (!open) panelOpen = false
        }
      }
    val viewportInsets =
      safeInsets.withLeadingPanel(panelWidth, panelProgress.value, layoutDirection)
    val hiddenTranslation =
      with(density) {
        val distance =
          when (layoutDirection) {
            LayoutDirection.Ltr -> safeInsets.left + panelWidth + ShellSpacing * 2
            LayoutDirection.Rtl -> safeInsets.right + panelWidth + ShellSpacing * 2
          }
        distance.toPx() * if (layoutDirection == LayoutDirection.Ltr) -1 else 1
      }
    val panelTranslation = hiddenTranslation * (1f - panelProgress.value)

    // Every control sits inside the map's rectangle, so directional focus search never reaches the
    // map from the handle or the handle from the map.
    val handleFocusRequester = remember { FocusRequester() }
    val mapFocusRequester = remember { FocusRequester() }
    val zoomButtonsFocusRequester = remember { FocusRequester() }
    val zoomButtonsShown = state.shell != DemoShell.Benchmarks && state.settings.showZoomButtons

    val handleTranslation =
      with(density) {
        val trailingEdge =
          lerp(0.dp, ShellSpacing + panelWidth - HandleOverlap, panelProgress.value)
        when (layoutDirection) {
          LayoutDirection.Ltr -> (safeInsets.left + trailingEdge).toPx()
          LayoutDirection.Rtl -> -(safeInsets.right + trailingEdge).toPx()
        }
      }

    Box(Modifier.fillMaxSize()) {
      val mapCovered = layout == DemoShellLayout.Compact && panelOpen
      Box(
        Modifier.fillMaxSize()
          .focusRequester(mapFocusRequester)
          .focusProperties {
            canFocus = !mapCovered
            start = handleFocusRequester
            // A requester with no node throws on use, so the route exists only with the buttons.
            if (zoomButtonsShown) end = zoomButtonsFocusRequester
          }
          .semantics { if (mapCovered) hideFromAccessibility() }
      ) {
        ShellMap(
          state = state,
          viewportInsets = viewportInsets,
          mapFocusRequester = mapFocusRequester,
          zoomButtonsFocusRequester = zoomButtonsFocusRequester,
        )
      }
      // Behind the panel, so the panel hides the tucked-under part and its shadow.
      PanelToggleHandle(
        panelOpen = panelOpen,
        onClick = { scope.launch { setPanelOpen(!panelOpen) } },
        modifier =
          Modifier.align(Alignment.CenterStart)
            .graphicsLayer { translationX = handleTranslation }
            .focusRequester(handleFocusRequester)
            .focusProperties { end = mapFocusRequester },
      )
      Box(
        modifier =
          Modifier.align(Alignment.CenterStart)
            .fillMaxHeight()
            .padding(safeInsets.asPaddingValues())
            .padding(ShellSpacing)
            .graphicsLayer { translationX = panelTranslation }
            .semantics { if (!panelOpen) hideFromAccessibility() }
      ) {
        Surface(
          modifier = Modifier.width(panelWidth).fillMaxHeight(),
          shape = MaterialTheme.shapes.extraLarge,
          tonalElevation = 2.dp,
          shadowElevation = 8.dp,
        ) {
          DemoPanel(
            state = state,
            modifier = Modifier.fillMaxSize().padding(vertical = 8.dp),
            collapsePanel = { setPanelOpen(false) },
            collapseOnSelection = layout == DemoShellLayout.Compact,
          )
        }
      }
    }
  }
}

/** The tab on the panel's trailing edge that collapses it, left at the screen edge to reopen it. */
@Composable
private fun PanelToggleHandle(
  panelOpen: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Surface(
    onClick = onClick,
    modifier = modifier.width(HandleWidth).height(HandleHeight),
    shape = RoundedCornerShape(topEnd = HandleWidth / 2, bottomEnd = HandleWidth / 2),
    tonalElevation = 2.dp,
    shadowElevation = 8.dp,
  ) {
    Box(
      Modifier.fillMaxSize().padding(start = HandleOverlap),
      contentAlignment = Alignment.Center,
    ) {
      Icon(
        vectorResource(
          if (panelOpen) Res.drawable.chevron_left_24px else Res.drawable.chevron_right_24px
        ),
        contentDescription = if (panelOpen) "Collapse demo panel" else "Expand demo panel",
      )
    }
  }
}

private fun resolvedPanelWidth(
  viewportWidth: Dp,
  safeInsets: MapViewportInsets,
  layout: DemoShellLayout,
): Dp {
  val availableWidth =
    (viewportWidth - safeInsets.left - safeInsets.right - ShellSpacing * 2).coerceAtLeast(0.dp)
  return when (layout) {
    DemoShellLayout.Expanded -> minOf(ExpandedPanelWidth, availableWidth)
    DemoShellLayout.Medium -> minOf(MediumPanelWidth, availableWidth)
    // Leave room beside a full-width panel for the handle, as in the wider modes.
    DemoShellLayout.Compact -> (availableWidth - HandleProtrusion).coerceAtLeast(0.dp)
  }
}

private fun WindowSizeClass.toDemoShellLayout(): DemoShellLayout =
  when {
    isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND) ->
      DemoShellLayout.Expanded
    isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND) -> DemoShellLayout.Medium
    else -> DemoShellLayout.Compact
  }

private fun MapViewportInsets.withLeadingPanel(
  panelWidth: Dp,
  visibleFraction: Float,
  layoutDirection: LayoutDirection,
): MapViewportInsets {
  val panelOcclusion = (ShellSpacing + panelWidth + ShellSpacing) * visibleFraction
  val panelEdge =
    when (layoutDirection) {
      LayoutDirection.Ltr -> MapViewportInsets(left = left + panelOcclusion)
      LayoutDirection.Rtl -> MapViewportInsets(right = right + panelOcclusion)
    }
  return union(panelEdge)
}

@Composable
private fun ShellMap(
  state: DemoAppState,
  viewportInsets: MapViewportInsets,
  mapFocusRequester: FocusRequester,
  zoomButtonsFocusRequester: FocusRequester,
) {
  if (state.shell == DemoShell.Benchmarks) {
    BenchmarkMap(state, viewportInsets)
  } else {
    DemoMap(state, viewportInsets, mapFocusRequester, zoomButtonsFocusRequester)
  }
}
