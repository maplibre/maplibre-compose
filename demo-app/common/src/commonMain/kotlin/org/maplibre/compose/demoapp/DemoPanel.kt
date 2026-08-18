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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import org.jetbrains.compose.resources.vectorResource
import org.maplibre.compose.demoapp.design.SectionHeader
import org.maplibre.compose.demoapp.design.SwitchRow
import org.maplibre.compose.demoapp.generated.Res
import org.maplibre.compose.demoapp.generated.arrow_back_24px
import org.maplibre.compose.demoapp.generated.settings_24px
import org.maplibre.compose.demoapp.generated.speed_24px

/** The demo list, the style knob, and the selected demo's controls — or the settings page. */
@Composable
fun DemoPanel(state: DemoAppState, modifier: Modifier = Modifier) {
  val navController = rememberNavController()
  val route = navController.currentBackStackEntryAsState().value?.destination?.route
  // selectedDemo drives the map overlay. Keep it aligned with this destination so
  // system and predictive back clear the overlay too.
  LaunchedEffect(route) {
    if (route == "demos") {
      state.selectedDemo = null
      state.shell = DemoShell.Demos
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
        onOpenSettings = { navController.navigate("settings") },
        onOpenDemo = { demo ->
          state.selectedDemo = demo
          navController.navigate("demo")
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
      ) {
        Text(
          text = demo.description,
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(horizontal = 16.dp),
        )
        demo.Panel()
      }
    }
    composable("benchmarks") {
      BenchmarksScreen(
        onBack = { navController.popBackStack() },
        onOpenScenario = { scenario ->
          state.selectedScenario = scenario
          navController.navigate("benchmark")
        },
      )
    }
    composable("benchmark") {
      val scenario = state.selectedScenario
      SettingsSubScreen(scenario.title, onBack = { navController.popBackStack() }) {
        BenchmarkScenarioPanel(state)
      }
    }
    composable("settings") {
      SettingsScreen(
        onBack = { navController.popBackStack() },
        onOpen = { navController.navigate("settings/$it") },
      )
    }
    composable("settings/gestures") {
      SettingsSubScreen("Gestures", onBack = { navController.popBackStack() }) {
        GestureSettingsItems(state.settings)
      }
    }
    composable("settings/rendering") {
      SettingsSubScreen("Rendering", onBack = { navController.popBackStack() }) {
        RenderSettingsItems(state.settings)
      }
    }
    composable("settings/interface") {
      SettingsSubScreen("Interface", onBack = { navController.popBackStack() }) {
        InterfaceSettingsItems(state.settings)
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
  onOpenSettings: () -> Unit,
  onOpenDemo: (Demo) -> Unit,
  onOpenBenchmarks: () -> Unit,
) {
  Column {
    TopAppBar(
      title = { Text("MapLibre Compose") },
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
    Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
      StyleSelector(state)

      SectionHeader("Demos")
      allDemos.forEach { demo ->
        SubmenuRow(demo.name, demo.description) { onOpenDemo(demo) }
      }
    }
  }
}

@Composable
private fun StyleSelector(state: DemoAppState) {
  var expanded by remember { mutableStateOf(false) }
  ExposedDropdownMenuBox(
    expanded = expanded,
    onExpandedChange = { expanded = it },
    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
  ) {
    OutlinedTextField(
      value = state.selectedStyle.displayName,
      onValueChange = {},
      readOnly = true,
      label = { Text("Map style") },
      trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
      modifier =
        Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
    )
    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
      DemoStyle.all.forEach { style ->
        DropdownMenuItem(
          text = { Text(style.displayName) },
          onClick = {
            state.selectedStyle = style
            expanded = false
          },
        )
      }
    }
  }
}

@Composable
private fun SettingsScreen(onBack: () -> Unit, onOpen: (route: String) -> Unit) {
  SettingsSubScreen("Settings", onBack) {
    SubmenuRow("Gestures", "Which inputs move the camera") { onOpen("gestures") }
    SubmenuRow("Rendering", "Frame rate cap and debug views") { onOpen("rendering") }
    SubmenuRow("Interface", "Map controls and diagnostic overlays") { onOpen("interface") }
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
private fun InterfaceSettingsItems(settings: DemoSettings) {
  SectionHeader("Map controls")
  SwitchRow("Material 3 controls", settings.useMaterial3Controls) {
    settings.useMaterial3Controls = it
  }

  SectionHeader("Overlays")
  SwitchRow("Frame rate", settings.showFpsOverlay) { settings.showFpsOverlay = it }
  SwitchRow("Camera state", settings.showCameraOverlay) { settings.showCameraOverlay = it }
}

@Composable
internal fun SettingsSubScreen(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
  Column {
    TopAppBar(
      title = { Text(title) },
      navigationIcon = {
        IconButton(onClick = onBack) {
          Icon(vectorResource(Res.drawable.arrow_back_24px), contentDescription = "Back")
        }
      },
      colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
    )
    Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) { content() }
  }
}
