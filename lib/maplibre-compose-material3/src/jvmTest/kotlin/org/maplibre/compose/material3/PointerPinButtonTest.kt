package org.maplibre.compose.material3

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class PointerPinButtonTest {
  @Test
  fun mapProjectedTargetIsConvertedToInsetChildCoordinates() = runComposeUiTest {
    setContent {
      MaterialTheme {
        val density = LocalDensity.current
        Box(Modifier.size(300.dp)) {
          PointerPinPlacement(
            target = with(density) { Offset(170.dp.toPx(), (-100).dp.toPx()) },
            modifier = Modifier.offset(40.dp, 60.dp).size(260.dp, 240.dp),
          ) { placement, _ ->
            val placementDp = with(density) { placement.x.toDp() to placement.y.toDp() }
            Box(
              Modifier.absoluteOffset(placementDp.first, placementDp.second)
                .size(20.dp)
                .testTag(INSET_PLACEMENT)
            )
          }
        }
      }
    }

    val placement = onNodeWithTag(INSET_PLACEMENT).getUnclippedBoundsInRoot()
    assertEquals(170f, placement.left.value, absoluteTolerance = 0.5f)
    assertEquals(60f, placement.top.value, absoluteTolerance = 0.5f)
  }

  private companion object {
    const val INSET_PLACEMENT = "inset-placement"
  }
}
