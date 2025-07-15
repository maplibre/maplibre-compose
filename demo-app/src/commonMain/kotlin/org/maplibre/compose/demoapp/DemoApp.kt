package org.maplibre.compose.demoapp

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.vectorResource
import org.maplibre.compose.demoapp.generated.Res
import org.maplibre.compose.demoapp.generated.keyboard_arrow_up_24px
import org.maplibre.compose.demoapp.util.getDefaultColorScheme

@Composable
fun DemoApp() {
  val demoState = rememberDemoState()
  val sheetState = rememberBottomSheetScaffoldState()

  MaterialTheme(colorScheme = getDefaultColorScheme(isDark = demoState.selectedStyle.isDark)) {
    BottomSheetScaffold(
      sheetPeekHeight = 128.dp, // TODO dynamic peek based on selected demo
      scaffoldState = sheetState,
      sheetSwipeEnabled = true,
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
          state = demoState,
          // TODO this doesn't work well on landscape and small screens
          modifier =
            Modifier.background(BottomSheetDefaults.ContainerColor)
              .consumeWindowInsets(PaddingValues(top = 56.dp))
              .requiredHeight(500.dp),
        )
      },
    ) { padding ->
      DemoMap(demoState, padding)
    }
  }
}

@Composable
private fun ExpandCollapseButton(
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
