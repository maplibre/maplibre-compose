package dev.sargunv.maplibrecompose.demoapp.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.rememberNavController
import dev.sargunv.maplibrecompose.compose.rememberCameraState
import dev.sargunv.maplibrecompose.compose.rememberStyleState
import dev.sargunv.maplibrecompose.demoapp.Protomaps
import dev.sargunv.maplibrecompose.demoapp.StyleInfo
import dev.sargunv.maplibrecompose.demoapp.generated.Res
import dev.sargunv.maplibrecompose.demoapp.generated.keyboard_arrow_up_24px
import dev.sargunv.maplibrecompose.demoapp.ui.pages.Routes
import dev.sargunv.maplibrecompose.demoapp.util.getDefaultColorScheme
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.vectorResource

@Composable
fun MapScreen() {
  val styleState = rememberStyleState()
  val cameraState = rememberCameraState()
  val sheetState = rememberBottomSheetScaffoldState()
  var chosenStyle by remember { mutableStateOf<StyleInfo>(Protomaps.Light) }

  @Composable
  fun ExpandCollapseButton(
    expanded: Boolean,
    onExpand: suspend () -> Unit,
    onCollapse: suspend () -> Unit,
    modifier: Modifier = Modifier,
  ) {
    val degrees by animateFloatAsState(targetValue = if (expanded) 180f else 0f)
    val coroutineScope = rememberCoroutineScope()
    IconButton(
      modifier = modifier,
      onClick = { coroutineScope.launch { if (expanded) onCollapse() else onExpand() } },
    ) {
      Icon(
        vectorResource(Res.drawable.keyboard_arrow_up_24px),
        contentDescription = if (expanded) "Collapse" else "Expand",
        modifier = Modifier.rotate(degrees),
      )
    }
  }

  MaterialTheme(colorScheme = getDefaultColorScheme(isDark = chosenStyle.isDark)) {
    BottomSheetScaffold(
      scaffoldState = sheetState,
      sheetSwipeEnabled = false,
      sheetDragHandle = {
        ExpandCollapseButton(
          sheetState.bottomSheetState.targetValue == SheetValue.Expanded,
          onExpand = { sheetState.bottomSheetState.expand() },
          onCollapse = { sheetState.bottomSheetState.partialExpand() },
          modifier = Modifier.fillMaxWidth(),
        )
      },
      sheetContent = {
        DemoSheetContent(
          Routes.Context(
            nav = rememberNavController(),
            modifier =
              Modifier.consumeWindowInsets(PaddingValues(top = 56.dp)).requiredHeight(500.dp),
            cameraState = cameraState,
            styleState = styleState,
            selectedStyle = chosenStyle,
            onStyleSelected = { chosenStyle = it },
          )
        )
      },
    ) { padding ->
      DemoMap(padding, styleState, cameraState, chosenStyle)
    }
  }
}
