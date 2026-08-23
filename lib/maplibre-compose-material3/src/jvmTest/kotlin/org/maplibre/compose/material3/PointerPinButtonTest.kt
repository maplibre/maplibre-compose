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
  fun parentTranslationMovesThePlacementOnce() = runComposeUiTest {
    setContent {
      MaterialTheme {
        val density = LocalDensity.current
        Box(Modifier.size(300.dp)) {
          PointerPinPlacement(
            target = with(density) { Offset(100.dp.toPx(), (-100).dp.toPx()) },
            modifier = Modifier.size(200.dp, 100.dp),
          ) { placement, _ ->
            val placementDp = with(density) { placement.x.toDp() to placement.y.toDp() }
            Box(
              Modifier.absoluteOffset(placementDp.first, placementDp.second)
                .size(20.dp)
                .testTag(BASE_PLACEMENT)
            )
          }
          PointerPinPlacement(
            target = with(density) { Offset(140.dp.toPx(), (-40).dp.toPx()) },
            modifier = Modifier.offset(40.dp, 60.dp).size(200.dp, 100.dp),
          ) { placement, _ ->
            val placementDp = with(density) { placement.x.toDp() to placement.y.toDp() }
            Box(
              Modifier.absoluteOffset(placementDp.first, placementDp.second)
                .size(20.dp)
                .testTag(TRANSLATED_PLACEMENT)
            )
          }
        }
      }
    }

    val base = onNodeWithTag(BASE_PLACEMENT).getUnclippedBoundsInRoot()
    val translated = onNodeWithTag(TRANSLATED_PLACEMENT).getUnclippedBoundsInRoot()

    assertEquals(40f, (translated.left - base.left).value, absoluteTolerance = 0.5f)
    assertEquals(60f, (translated.top - base.top).value, absoluteTolerance = 0.5f)
  }

  private companion object {
    const val BASE_PLACEMENT = "base-placement"
    const val TRANSLATED_PLACEMENT = "translated-placement"
  }
}
