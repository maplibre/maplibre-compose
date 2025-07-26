package org.maplibre.compose.util

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.toSize

@Composable
internal fun MeasuredBox(
  modifier: Modifier,
  contents: @Composable (x: Dp, y: Dp, width: Dp, height: Dp) -> Unit,
) {
  val density = LocalDensity.current
  var positionInParent by remember { mutableStateOf<Offset?>(null) }
  var size by remember { mutableStateOf<IntSize?>(null) }
  Box(
    modifier =
      modifier.onGloballyPositioned {
        positionInParent = it.positionInParent()
        size = it.size
      }
  ) {
    positionInParent?.let { positionInParent ->
      size?.let { size ->
        with(density) {
          val dpSize = size.toSize().toDpSize()
          val x = positionInParent.x.toDp()
          val y = positionInParent.y.toDp()
          if (dpSize.isSpecified) {
            contents(x, y, dpSize.width, dpSize.height)
          }
        }
      }
    }
  }
}
