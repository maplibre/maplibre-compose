package org.maplibre.compose.demoapp

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.window.core.layout.WindowSizeClass
import kotlinx.coroutines.launch
import org.maplibre.compose.demoapp.benchmark.BenchmarkMap

@Composable
fun DemoApp() {
  val state = rememberDemoAppState()
  val dark =
    if (state.shell == DemoShell.Benchmarks) state.selectedScenario.style.isDark
    else state.appliedStyle.isDark
  val colorScheme = rememberDemoColorScheme(dark, state.settings.paletteMode)
  MaterialTheme(colorScheme = colorScheme) {
    DemoShell(state)
  }
}

private val MediumPanelWidth = 280.dp
private val ExpandedPanelWidth = 360.dp
private val ShellSpacing = 16.dp
private const val PanelMotionDurationMillis = 220
private val PanelMotionEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

@Composable
private fun DemoShell(state: DemoAppState) {
  BoxWithConstraints(Modifier.fillMaxSize()) {
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val safeInsets = WindowInsets.safeDrawing.toMapViewportInsets(density, layoutDirection)
    val windowAdaptiveInfo = currentWindowAdaptiveInfoV2()
    val windowSizeClass = windowAdaptiveInfo.windowSizeClass
    val isMediumOrWider =
      windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND)
    val isExpandedOrWider =
      windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND)
    val panelWidth = resolvedPanelWidth(maxWidth, safeInsets, isMediumOrWider, isExpandedOrWider)
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

    Box(Modifier.fillMaxSize()) {
      Box(
        Modifier.fillMaxSize().semantics {
          if (!isMediumOrWider && panelOpen) hideFromAccessibility()
        }
      ) {
        ShellMap(
          state = state,
          viewportInsets = viewportInsets,
          showOpenPanelButton = !panelOpen,
          onOpenPanel = { scope.launch { setPanelOpen(true) } },
        )
      }
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
            collapseOnSelection = !isMediumOrWider,
          )
        }
      }
    }
  }
}

private fun resolvedPanelWidth(
  viewportWidth: Dp,
  safeInsets: MapViewportInsets,
  isMediumOrWider: Boolean,
  isExpandedOrWider: Boolean,
): Dp {
  val availableWidth =
    (viewportWidth - safeInsets.left - safeInsets.right - ShellSpacing * 2).coerceAtLeast(0.dp)
  return when {
    isExpandedOrWider -> minOf(ExpandedPanelWidth, availableWidth)
    isMediumOrWider -> minOf(MediumPanelWidth, availableWidth)
    else -> availableWidth
  }
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
  showOpenPanelButton: Boolean,
  onOpenPanel: () -> Unit,
) {
  if (state.shell == DemoShell.Benchmarks) {
    BenchmarkMap(state, viewportInsets, showOpenPanelButton, onOpenPanel)
  } else {
    DemoMap(state, viewportInsets, showOpenPanelButton, onOpenPanel)
  }
}
