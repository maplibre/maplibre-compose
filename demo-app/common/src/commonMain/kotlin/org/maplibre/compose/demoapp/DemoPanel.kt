package org.maplibre.compose.demoapp

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.vectorResource
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.demoapp.design.ButtonRow
import org.maplibre.compose.demoapp.design.DropdownRow
import org.maplibre.compose.demoapp.design.SectionHeader
import org.maplibre.compose.demoapp.design.SegmentedRow
import org.maplibre.compose.demoapp.design.SliderRow
import org.maplibre.compose.demoapp.design.SwitchRow
import org.maplibre.compose.demoapp.generated.Res
import org.maplibre.compose.demoapp.generated.arrow_back_24px
import org.maplibre.compose.demoapp.generated.settings_24px
import org.maplibre.compose.demoapp.generated.speed_24px
import org.maplibre.spatialk.geojson.Position

/** The panel's navigation routes. The shell reads the current one to size its surface. */
internal object DemoRoute {
  const val Demos = "demos"
  const val Demo = "demo"
  const val Benchmarks = "benchmarks"
  const val Benchmark = "benchmark"
  const val Settings = "settings"
  const val LocationSettings = "settings/location"
  const val InputSettings = "settings/input"
  const val CameraSettings = "settings/camera"
  const val RenderingSettings = "settings/rendering"
}

/** The height of a panel screen's top app bar, which is the peek content on every other route. */
internal val PanelHeaderHeight = 64.dp

/**
 * The menu, settings, and the selected demo's controls. [revealMap] uncovers the map when a control
 * needs it visible; a shell whose panel never covers the map passes a no-op. [onPeekHeightChange]
 * reports the height of the selected demo's title bar, peek row, and [peekSpacing] below them,
 * which a sheet keeps visible above the window's bottom inset.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun DemoPanel(
  state: DemoAppState,
  navController: NavHostController,
  modifier: Modifier = Modifier,
  revealMap: suspend () -> Unit = {},
  peekSpacing: Dp = 0.dp,
  onPeekHeightChange: (Dp) -> Unit = {},
) {
  val scope = rememberCoroutineScope()
  val dark = state.settings.mapStyleMode.isDark
  var flightJob by remember { mutableStateOf<Job?>(null) }
  val route = navController.currentBackStackEntryAsState().value?.destination?.route
  // selectedDemo drives the map overlay. Keep it aligned with this destination so
  // system and predictive back clear the overlay too.
  LaunchedEffect(route) {
    if (route != DemoRoute.LocationSettings) state.location.cancelMockPlacement()
    if (route == DemoRoute.Demos) {
      flightJob?.cancel()
      state.selectedDemo = null
      state.shell = DemoShell.Demos
      state.benchmark.abandonRun()
    }
  }
  val motion = MaterialTheme.motionScheme
  NavHost(
    navController = navController,
    startDestination = DemoRoute.Demos,
    modifier = modifier,
    enterTransition = { motion.forwardEnter() },
    exitTransition = { motion.forwardExit() },
    popEnterTransition = { motion.backwardEnter() },
    popExitTransition = { motion.backwardExit() },
  ) {
    composable(DemoRoute.Demos) {
      DemosScreen(
        onOpenSettings = { navController.navigate(DemoRoute.Settings) },
        onOpenDemo = { demo ->
          flightJob?.cancel()
          flightJob = scope.launch {
            state.openDemo(demo, dark) {
              navController.navigate(DemoRoute.Demo)
              revealMap()
              // One frame so the settled viewport insets reach the camera before the flight.
              withFrameNanos {}
            }
          }
        },
        onOpenBenchmarks = {
          state.selectedDemo = null
          state.shell = DemoShell.Benchmarks
          navController.navigate(DemoRoute.Benchmarks)
        },
      )
    }
    composable(DemoRoute.Demo) {
      val demo = state.selectedDemo ?: return@composable
      val density = LocalDensity.current
      SettingsSubScreen(
        demo.name,
        onBack = { navController.popBackStack() },
        header = {
          demo.PeekPanel(state)
          Spacer(Modifier.height(peekSpacing))
        },
        headerModifier =
          Modifier.onSizeChanged { onPeekHeightChange(with(density) { it.height.toDp() }) },
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
    composable(DemoRoute.Benchmarks) {
      BenchmarksScreen(
        onBack = { navController.popBackStack() },
        onOpenScenario = { scenario ->
          state.benchmark.abandonRun()
          state.selectedScenario = scenario
          navController.navigate(DemoRoute.Benchmark)
        },
      )
    }
    composable(DemoRoute.Benchmark) {
      val scenario = state.selectedScenario
      SettingsSubScreen(scenario.title, onBack = { navController.popBackStack() }) {
        BenchmarkScenarioPanel(
          state,
          onRun = {
            scope.launch {
              revealMap()
              state.benchmark.requestRun()
            }
          },
        )
      }
    }
    composable(DemoRoute.Settings) {
      SettingsScreen(
        state,
        onBack = { navController.popBackStack() },
        onOpen = { navController.navigate(it) },
      )
    }
    composable(DemoRoute.LocationSettings) {
      SettingsSubScreen("Location", onBack = { navController.popBackStack() }) {
        LocationSettingsItems(state.location) { state.mapState.cameraPosition.target }
        if (state.location.isMock) {
          MockLocationSettings(state) {
            state.location.beginMockPlacement()
            scope.launch { revealMap() }
          }
        }
      }
    }
    composable(DemoRoute.InputSettings) {
      SettingsSubScreen("Input", onBack = { navController.popBackStack() }) {
        InputSettingsItems(state.settings)
      }
    }
    composable(DemoRoute.CameraSettings) {
      SettingsSubScreen("Camera", onBack = { navController.popBackStack() }) {
        CameraSettingsItems(state)
      }
    }
    composable(DemoRoute.RenderingSettings) {
      SettingsSubScreen("Rendering", onBack = { navController.popBackStack() }) {
        TileLodSettingsItems(state.settings)
        RenderSettingsItems(state.settings)
        OverlaySettingsItems(state.settings)
      }
    }
  }
}

// Material 3 forward and backward: the child screen slides across the full panel width while the
// parent slides a quarter of the way and fades. One screen always covers the panel, so it never
// shows empty mid-transition, unlike the shared axis fade through. The theme's motion scheme
// supplies the specs: spatial for the slides, effects for the fades.
private const val ParentSlideFraction = 4

private fun MotionScheme.forwardEnter(): EnterTransition =
  slideInHorizontally(defaultSpatialSpec()) { it }

private fun MotionScheme.forwardExit(): ExitTransition =
  slideOutHorizontally(defaultSpatialSpec()) { -it / ParentSlideFraction } +
    fadeOut(defaultEffectsSpec())

private fun MotionScheme.backwardEnter(): EnterTransition =
  slideInHorizontally(defaultSpatialSpec()) { -it / ParentSlideFraction } +
    fadeIn(defaultEffectsSpec())

private fun MotionScheme.backwardExit(): ExitTransition =
  slideOutHorizontally(defaultSpatialSpec()) { it }

@Composable
private fun DemosScreen(
  onOpenSettings: () -> Unit,
  onOpenDemo: (Demo) -> Unit,
  onOpenBenchmarks: () -> Unit,
) {
  PanelScreen(
    header = {
      TopAppBar(
        title = { Text("Demos") },
        actions = {
          IconButton(onClick = onOpenBenchmarks) {
            Icon(vectorResource(Res.drawable.speed_24px), contentDescription = "Benchmarks")
          }
          IconButton(onClick = onOpenSettings) {
            Icon(vectorResource(Res.drawable.settings_24px), contentDescription = "Settings")
          }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
      )
    }
  ) {
    allDemos.forEach { demo -> SubmenuRow(demo.name, demo.description) { onOpenDemo(demo) } }
  }
}

@Composable
private fun SettingsScreen(
  state: DemoAppState,
  onBack: () -> Unit,
  onOpen: (route: String) -> Unit,
) {
  SettingsSubScreen("Settings", onBack) {
    SectionHeader("Map style")
    SegmentedRow(
      options = MapStyleMode.entries,
      selected = state.settings.mapStyleMode,
      optionLabel = { it.displayName },
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
    SubmenuRow("Location", "Provider, mock position, heading, and accuracy") {
      onOpen(DemoRoute.LocationSettings)
    }
    SubmenuRow("Input", "Gestures, scroll wheel, and map controls") {
      onOpen(DemoRoute.InputSettings)
    }
    SubmenuRow("Camera", "How the camera flies to demos, pins, and your location") {
      onOpen(DemoRoute.CameraSettings)
    }
    SubmenuRow("Rendering", "Tile detail, frame rate cap, debug views, and overlays") {
      onOpen(DemoRoute.RenderingSettings)
    }
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
private fun InputSettingsItems(settings: DemoSettings) {
  SectionHeader("Gestures")
  SwitchRow("Pan", settings.panEnabled) { settings.panEnabled = it }
  SwitchRow("Rotate", settings.rotateEnabled) { settings.rotateEnabled = it }
  SwitchRow("Tilt", settings.tiltEnabled) { settings.tiltEnabled = it }
  Text(
    "Off, the map keeps its heading or tilt across every input method. Demos that need a " +
      "movement keep it on.",
    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    style = MaterialTheme.typography.bodyMedium,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
  )

  SectionHeader("Scroll wheel")
  SegmentedRow(
    options = listOf(false, true),
    selected = settings.scrollPans,
    optionLabel = { if (it) "Pan" else "Zoom" },
    onSelect = { settings.scrollPans = it },
  )
  Text(
    "Pan moves the map with a wheel or trackpad; hold Ctrl to zoom. Touch is unaffected.",
    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    style = MaterialTheme.typography.bodyMedium,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
  )

  SectionHeader("Map controls")
  SwitchRow("Material 3 controls", settings.useMaterial3Controls) {
    settings.useMaterial3Controls = it
  }
  SwitchRow("Zoom buttons", settings.showZoomButtons) { settings.showZoomButtons = it }
}

/** Places far enough apart that a flight between them shows the pacing and minimum zoom. */
private enum class FlightDestination(val title: String, val camera: CameraPosition) {
  Seattle("Seattle", CameraPosition(target = Position(-122.3352, 47.6205), zoom = 14.0)),
  NewYork("New York", CameraPosition(target = Position(-74.006, 40.7128), zoom = 13.0)),
  London("London", CameraPosition(target = Position(-0.1276, 51.5072), zoom = 12.0)),
}

@Composable
private fun CameraSettingsItems(state: DemoAppState) {
  val settings = state.settings
  SectionHeader("Flight")
  SegmentedRow(
    options = FlightStyle.entries,
    selected = settings.flightStyle,
    optionLabel = { it.title },
    onSelect = { settings.flightStyle = it },
  )
  val fly = settings.flightStyle == FlightStyle.Fly
  if (fly) {
    SwitchRow("Pace by speed", settings.paceFlightBySpeed) { settings.paceFlightBySpeed = it }
  }
  if (fly && settings.paceFlightBySpeed) {
    SliderRow(
      label = "Speed",
      value = settings.flightSpeed,
      range = 0.5f..10f,
      valueLabel = { "${(it * 10).roundToInt() / 10f} screens/s" },
      onChange = { settings.flightSpeed = it },
    )
  } else {
    SliderRow(
      label = "Duration",
      value = settings.flightDurationMillis,
      range = 200f..5000f,
      valueLabel = { "${it.roundToInt()} ms" },
      onChange = { settings.flightDurationMillis = it },
    )
  }
  if (fly) {
    SliderRow(
      label = "Minimum zoom",
      value = settings.flightMinZoom,
      range = 0f..12f,
      valueLabel = { if (it > 0f) it.roundToInt().toString() else "None" },
      onChange = { settings.flightMinZoom = it.roundToInt().toFloat() },
    )
  }
  Text(
    "Applies when a demo opens, a pointer pin is pressed, and when following your location.",
    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    style = MaterialTheme.typography.bodyMedium,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
  )

  SectionHeader("Try it")
  val scope = rememberCoroutineScope()
  for (destination in FlightDestination.entries) {
    ButtonRow("Fly to ${destination.title}") {
      scope.launch {
        state.mapState.flyTo(
          DemoDestination.ExactCamera(destination.camera),
          settings.flightAnimation,
        )
      }
    }
  }
}

@Composable
private fun OverlaySettingsItems(settings: DemoSettings) {
  SectionHeader("Overlays")
  SwitchRow("Frame rate", settings.showFpsOverlay) { settings.showFpsOverlay = it }
  SwitchRow("Camera state", settings.showCameraOverlay) { settings.showCameraOverlay = it }
  SwitchRow("Pointer pin geometry", settings.showPointerPinDiagnostics) {
    settings.showPointerPinDiagnostics = it
  }
}

/**
 * A titled screen with a back button. [header] sits under the title, ahead of [content], and
 * [headerModifier] wraps the title bar and header together.
 */
@Composable
internal fun SettingsSubScreen(
  title: String,
  onBack: () -> Unit,
  header: @Composable () -> Unit = {},
  headerModifier: Modifier = Modifier,
  content: @Composable () -> Unit,
) {
  PanelScreen(
    header = {
      TopAppBar(
        title = { Text(title) },
        navigationIcon = {
          IconButton(onClick = onBack) {
            Icon(vectorResource(Res.drawable.arrow_back_24px), contentDescription = "Back")
          }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
      )
      header()
    },
    headerModifier = headerModifier,
    content = content,
  )
}

/**
 * A panel screen whose [header] scrolls with its [content], as a sheet's title does. The sheet peek
 * shows the header, so the screen scrolls back to it whenever the sheet returns to its peek.
 */
@Composable
private fun PanelScreen(
  header: @Composable () -> Unit,
  headerModifier: Modifier = Modifier,
  content: @Composable () -> Unit,
) {
  val scrollState = rememberScrollState()
  val peeking = LocalSheetPeeking.current
  LaunchedEffect(peeking) { if (peeking) scrollState.animateScrollTo(0) }
  Column(Modifier.verticalScroll(scrollState).padding(bottom = 16.dp)) {
    Column(headerModifier) { header() }
    content()
  }
}
