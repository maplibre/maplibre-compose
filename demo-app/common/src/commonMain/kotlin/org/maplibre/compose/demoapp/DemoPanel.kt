package org.maplibre.compose.demoapp

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.vectorResource
import org.maplibre.compose.demoapp.design.DropdownRow
import org.maplibre.compose.demoapp.design.SectionHeader
import org.maplibre.compose.demoapp.design.SegmentedRow
import org.maplibre.compose.demoapp.design.SwitchRow
import org.maplibre.compose.demoapp.generated.Res
import org.maplibre.compose.demoapp.generated.arrow_back_24px
import org.maplibre.compose.demoapp.generated.left_panel_close_24px
import org.maplibre.compose.demoapp.generated.settings_24px
import org.maplibre.compose.demoapp.generated.speed_24px

/** The demo list, the style knob, and the selected demo's controls — or the settings page. */
@Composable
fun DemoPanel(
  state: DemoAppState,
  modifier: Modifier = Modifier,
  collapsePanel: suspend () -> Unit = {},
  collapseOnSelection: Boolean = true,
) {
  val navController = rememberNavController()
  val scope = rememberCoroutineScope()
  val appliedStyle = state.appliedStyle
  val collapse: () -> Unit = { scope.launch { collapsePanel() } }
  val route = navController.currentBackStackEntryAsState().value?.destination?.route
  // selectedDemo drives the map overlay. Keep it aligned with this destination so
  // system and predictive back clear the overlay too.
  LaunchedEffect(route) {
    if (route == "demos") {
      state.cancelDeferredDemoFlight()
      state.selectedDemo = null
      state.shell = DemoShell.Demos
      state.benchmark.abandonRun()
    }
  }
  // Material 3 shared axis X: siblings slide 30dp while fading through.
  val slideDistance = with(LocalDensity.current) { 30.dp.roundToPx() }
  NavHost(
    navController = navController,
    startDestination = "demos",
    modifier = modifier,
    enterTransition = { sharedAxisEnter(slideDistance) },
    exitTransition = { sharedAxisExit(-slideDistance) },
    popEnterTransition = { sharedAxisEnter(-slideDistance) },
    popExitTransition = { sharedAxisExit(slideDistance) },
  ) {
    composable("demos") {
      DemosScreen(
        state,
        onCollapsePanel = collapse,
        onOpenSettings = { navController.navigate("settings") },
        onOpenDemo = { demo ->
          scope.launch {
            state.cancelDeferredDemoFlight()
            val preferredStyle = demo.preferredStyle
            val changesStyle = preferredStyle != null && preferredStyle.base != appliedStyle.base
            if (!collapseOnSelection) {
              state.selectedDemo = demo
              navController.navigate("demo")
              state.cameraState.flyTo(demo.destination)
            } else if (!changesStyle) {
              collapsePanel()
              withFrameNanos {}
              state.selectedDemo = demo
              navController.navigate("demo")
              state.cameraState.flyTo(demo.destination)
            } else {
              state.deferDemoFlight(demo.destination)
              scope.launch {
                collapsePanel()
                withFrameNanos {}
                state.finishDeferredDemoPanelCollapse()?.let { destination ->
                  state.cameraState.flyTo(destination)
                }
              }
              state.selectedDemo = demo
              navController.navigate("demo")
            }
          }
        },
        onOpenBenchmarks = {
          state.selectedDemo = null
          state.shell = DemoShell.Benchmarks
          navController.navigate("benchmarks")
        },
      )
    }
    composable("demo") {
      val demo = state.selectedDemo ?: return@composable
      SettingsSubScreen(
        demo.name,
        onBack = { navController.popBackStack() },
        onCollapsePanel = collapse,
      ) {
        Text(
          text = demo.description,
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(horizontal = 16.dp),
        )
        demo.Panel(state)
      }
    }
    composable("benchmarks") {
      BenchmarksScreen(
        onBack = { navController.popBackStack() },
        onCollapsePanel = collapse,
        onOpenScenario = { scenario ->
          scope.launch {
            if (collapseOnSelection) collapsePanel()
            state.selectedScenario = scenario
            navController.navigate("benchmark")
          }
        },
      )
    }
    composable("benchmark") {
      val scenario = state.selectedScenario
      SettingsSubScreen(
        scenario.title,
        onBack = { navController.popBackStack() },
        onCollapsePanel = collapse,
      ) {
        BenchmarkScenarioPanel(state)
      }
    }
    composable("settings") {
      SettingsScreen(
        state,
        onBack = { navController.popBackStack() },
        onCollapsePanel = collapse,
        onOpen = { navController.navigate("settings/$it") },
      )
    }
    composable("settings/gestures") {
      SettingsSubScreen(
        "Gestures",
        onBack = { navController.popBackStack() },
        onCollapsePanel = collapse,
      ) {
        GestureSettingsItems(state.settings)
      }
    }
    composable("settings/rendering") {
      SettingsSubScreen(
        "Rendering",
        onBack = { navController.popBackStack() },
        onCollapsePanel = collapse,
      ) {
        TileLodSettingsItems(state.settings)
        RenderSettingsItems(state.settings)
      }
    }
    composable("settings/controls") {
      SettingsSubScreen(
        "Controls",
        onBack = { navController.popBackStack() },
        onCollapsePanel = collapse,
      ) {
        ControlSettingsItems(state.settings)
      }
    }
  }
}

private const val AxisDurationMillis = 300
private val StandardEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
private val AccelerateEasing = CubicBezierEasing(0.3f, 0f, 1f, 1f)
private val DecelerateEasing = CubicBezierEasing(0f, 0f, 0f, 1f)

private fun sharedAxisEnter(slideDistance: Int): EnterTransition =
  slideInHorizontally(tween(AxisDurationMillis, easing = StandardEasing)) { slideDistance } +
    fadeIn(tween(AxisDurationMillis * 7 / 10, AxisDurationMillis * 3 / 10, DecelerateEasing))

private fun sharedAxisExit(slideDistance: Int): ExitTransition =
  slideOutHorizontally(tween(AxisDurationMillis, easing = StandardEasing)) { slideDistance } +
    fadeOut(tween(AxisDurationMillis * 3 / 10, easing = AccelerateEasing))

@Composable
private fun DemosScreen(
  state: DemoAppState,
  onCollapsePanel: () -> Unit,
  onOpenSettings: () -> Unit,
  onOpenDemo: (Demo) -> Unit,
  onOpenBenchmarks: () -> Unit,
) {
  Column {
    TopAppBar(
      title = { Text("Demos") },
      actions = {
        CollapsePanelButton(onCollapsePanel)
        IconButton(onClick = onOpenBenchmarks) {
          Icon(vectorResource(Res.drawable.speed_24px), contentDescription = "Benchmarks")
        }
        IconButton(onClick = onOpenSettings) {
          Icon(vectorResource(Res.drawable.settings_24px), contentDescription = "Settings")
        }
      },
      colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
    )
    Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
      allDemos.forEach { demo ->
        SubmenuRow(demo.name, demo.description) { onOpenDemo(demo) }
      }
    }
  }
}

@Composable
private fun SettingsScreen(
  state: DemoAppState,
  onBack: () -> Unit,
  onCollapsePanel: () -> Unit,
  onOpen: (route: String) -> Unit,
) {
  SettingsSubScreen("Settings", onBack, onCollapsePanel) {
    SectionHeader("Map style")
    SegmentedRow(
      options = MapStyleMode.entries,
      selected = state.settings.mapStyleMode,
      optionLabel = { it.name },
      onSelect = { state.settings.mapStyleMode = it },
    )
    DropdownRow(
      label = "Light style",
      options = allDemoStyles.filter { !it.isDark },
      selected = state.chosenLightStyle,
      optionLabel = { it.displayName },
      onSelect = { state.chosenLightStyle = it },
    )
    DropdownRow(
      label = "Dark style",
      options = allDemoStyles.filter { it.isDark },
      selected = state.chosenDarkStyle,
      optionLabel = { it.displayName },
      onSelect = { state.chosenDarkStyle = it },
    )

    SectionHeader("Material theme")
    DropdownRow(
      label = "Palette",
      options = paletteModeOptions,
      selected = state.settings.paletteMode,
      optionLabel = { it.name },
      onSelect = { state.settings.paletteMode = it },
    )

    SectionHeader("Options")
    SubmenuRow("Gestures", "Which inputs move the camera") { onOpen("gestures") }
    SubmenuRow("Rendering", "Frame rate cap, tile detail, and debug views") { onOpen("rendering") }
    SubmenuRow("Controls", "Map controls and diagnostic overlays") { onOpen("controls") }
  }
}

@Composable
internal fun SubmenuRow(label: String, description: String, onClick: () -> Unit) {
  ListItem(
    headlineContent = { Text(label) },
    supportingContent = { Text(description) },
    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    modifier = Modifier.clickable(onClick = onClick),
  )
}

@Composable
private fun ControlSettingsItems(settings: DemoSettings) {
  SectionHeader("Map controls")
  SwitchRow("Material 3 controls", settings.useMaterial3Controls) {
    settings.useMaterial3Controls = it
  }

  SectionHeader("Overlays")
  SwitchRow("Frame rate", settings.showFpsOverlay) { settings.showFpsOverlay = it }
  SwitchRow("Camera state", settings.showCameraOverlay) { settings.showCameraOverlay = it }
  SwitchRow("Pointer pin geometry", settings.showPointerPinDiagnostics) {
    settings.showPointerPinDiagnostics = it
  }
}

@Composable
internal fun SettingsSubScreen(
  title: String,
  onBack: () -> Unit,
  onCollapsePanel: () -> Unit,
  content: @Composable () -> Unit,
) {
  Column {
    TopAppBar(
      title = { Text(title) },
      navigationIcon = {
        IconButton(onClick = onBack) {
          Icon(vectorResource(Res.drawable.arrow_back_24px), contentDescription = "Back")
        }
      },
      actions = { CollapsePanelButton(onCollapsePanel) },
      colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
    )
    Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) { content() }
  }
}

@Composable
private fun CollapsePanelButton(onClick: () -> Unit) {
  IconButton(onClick = onClick) {
    Icon(
      vectorResource(Res.drawable.left_panel_close_24px),
      contentDescription = "Collapse demo panel",
    )
  }
}
