package dev.sargunv.maplibrecompose.demoapp

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dev.sargunv.maplibrecompose.compose.CameraState
import dev.sargunv.maplibrecompose.compose.StyleState
import dev.sargunv.maplibrecompose.core.OrnamentSettings
import dev.sargunv.maplibrecompose.demoapp.demos.AnimatedLayerDemo
import dev.sargunv.maplibrecompose.demoapp.demos.CameraFollowDemo
import dev.sargunv.maplibrecompose.demoapp.demos.CameraStateDemo
import dev.sargunv.maplibrecompose.demoapp.demos.ClusteredPointsDemo
import dev.sargunv.maplibrecompose.demoapp.demos.EdgeToEdgeDemo
import dev.sargunv.maplibrecompose.demoapp.demos.FrameRateDemo
import dev.sargunv.maplibrecompose.demoapp.demos.LocalTilesDemo
import dev.sargunv.maplibrecompose.demoapp.demos.MarkersDemo
import dev.sargunv.maplibrecompose.demoapp.demos.StyleSwitcherDemo
import dev.sargunv.maplibrecompose.demoapp.demos.platformDemos
import dev.sargunv.maplibrecompose.demoapp.generated.Res
import dev.sargunv.maplibrecompose.demoapp.generated.arrow_back
import dev.sargunv.maplibrecompose.demoapp.generated.info
import dev.sargunv.maplibrecompose.material3.controls.AttributionButton
import dev.sargunv.maplibrecompose.material3.controls.DisappearingCompassButton
import dev.sargunv.maplibrecompose.material3.controls.DisappearingScaleBar
import dev.sargunv.maplibrecompose.material3.controls.ScaleBarMeasures
import dev.sargunv.maplibrecompose.material3.defaultScaleBarMeasures
import org.jetbrains.compose.resources.vectorResource

private val DEMOS = buildList {
  add(StyleSwitcherDemo)
  if (Platform.supportsBlending) add(EdgeToEdgeDemo)
  if (Platform.supportsLayers) {
    add(MarkersDemo)
    add(ClusteredPointsDemo)
    add(AnimatedLayerDemo)
    add(LocalTilesDemo)
  }
  if (!Platform.isDesktop) add(CameraStateDemo)
  if (Platform.usesMaplibreNative) add(CameraFollowDemo)
  if (!Platform.isDesktop) add(FrameRateDemo)
  addAll(platformDemos)
}

@Composable
fun DemoApp(navController: NavHostController = rememberNavController()) {
  MaterialTheme(colorScheme = getDefaultColorScheme()) {
    NavHost(
      navController = navController,
      startDestination = "start",
      enterTransition = { slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Start) },
      exitTransition = { slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Start) },
      popEnterTransition = {
        slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.End)
      },
      popExitTransition = { slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.End) },
    ) {
      composable("start") { DemoMenu { demo -> navController.navigate(demo.name) } }
      DEMOS.forEach { demo ->
        composable(demo.name) { demo.Component { navController.popBackStack() } }
      }
    }
  }
}

@Composable
private fun DemoMenu(navigate: (demo: Demo) -> Unit) {
  Scaffold(topBar = { TopAppBar(title = { Text("MapLibre Compose Demos") }) }) { padding ->
    Column(
      modifier =
        Modifier.consumeWindowInsets(padding).padding(padding).verticalScroll(rememberScrollState())
    ) {
      DEMOS.forEach { demo ->
        Column {
          ListItem(
            modifier = Modifier.clickable(onClick = { navigate(demo) }),
            headlineContent = { Text(text = demo.name) },
            supportingContent = { Text(text = demo.description) },
          )
          HorizontalDivider()
        }
      }
    }
  }
}

@Composable
fun DemoAppBar(demo: Demo, navigateUp: () -> Unit, alpha: Float = 1f) {
  var showInfo by remember { mutableStateOf(false) }

  TopAppBar(
    colors =
      TopAppBarDefaults.mediumTopAppBarColors(
        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = alpha)
      ),
    title = { Text(demo.name) },
    navigationIcon = {
      IconButton(onClick = navigateUp) {
        Icon(imageVector = vectorResource(Res.drawable.arrow_back), contentDescription = "Back")
      }
    },
    actions = {
      if (Platform.supportsBlending) {
        IconButton(onClick = { showInfo = true }) {
          Icon(imageVector = vectorResource(Res.drawable.info), contentDescription = "Info")
        }
      }
    },
  )

  if (showInfo) {
    AlertDialog(
      onDismissRequest = { showInfo = false },
      title = { Text(text = demo.name) },
      text = { Text(text = demo.description) },
      confirmButton = { TextButton(onClick = { showInfo = false }) { Text("OK") } },
    )
  }
}

@Composable
fun DemoScaffold(demo: Demo, navigateUp: () -> Unit, content: @Composable () -> Unit) {
  Scaffold(topBar = { DemoAppBar(demo, navigateUp) }) { padding ->
    Box(modifier = Modifier.consumeWindowInsets(padding).padding(padding)) { content() }
  }
}

@Composable
fun DemoMapControls(
  cameraState: CameraState,
  styleState: StyleState,
  modifier: Modifier = Modifier,
  onCompassClick: () -> Unit = {},
  scaleBarMeasures: ScaleBarMeasures = defaultScaleBarMeasures(),
) {
  if (Platform.supportsBlending) {
    Box(modifier = modifier.fillMaxSize().padding(8.dp)) {
      DisappearingScaleBar(
        metersPerDp = cameraState.metersPerDpAtTarget,
        zoom = cameraState.position.zoom,
        modifier = Modifier.align(Alignment.TopStart),
        measures = scaleBarMeasures,
      )
      DisappearingCompassButton(
        cameraState,
        modifier = Modifier.align(Alignment.TopEnd),
        onClick = onCompassClick,
      )
      AttributionButton(
        cameraState = cameraState,
        styleState = styleState,
        modifier = Modifier.align(Alignment.BottomEnd),
        contentAlignment = Alignment.BottomEnd,
      )
    }
  }
}

fun DemoOrnamentSettings(padding: PaddingValues = PaddingValues(0.dp)) =
  if (Platform.supportsBlending)
    OrnamentSettings.AllDisabled.copy(
      padding = padding,
      isLogoEnabled = true,
      logoAlignment = Alignment.BottomStart,
    )
  else OrnamentSettings.AllEnabled
