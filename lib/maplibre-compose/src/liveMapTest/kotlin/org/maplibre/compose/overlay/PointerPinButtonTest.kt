package org.maplibre.compose.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import org.maplibre.compose.map.MapPresentationOwnerToken
import org.maplibre.compose.map.MapSnapshotRequest
import org.maplibre.compose.map.PresentationTestAdapter
import org.maplibre.compose.map.mapRuntimeForTest
import org.maplibre.compose.map.viewportFor
import org.maplibre.compose.style.BaseStyle
import org.maplibre.spatialk.geojson.Position

@OptIn(ExperimentalTestApi::class)
class PointerPinButtonTest {
  // The content clips to the turned pin, so it stays visible whichever way the pin points.
  @Test fun content_draws_inside_a_pin_pointing_sideways() = assertPinCenterIsContent(Modifier)

  @Test
  fun a_pin_sized_by_the_caller_centers_its_content() =
    assertPinCenterIsContent(Modifier.size(96.dp))

  private fun assertPinCenterIsContent(modifier: Modifier) = runComposeUiTest {
    val runtime = mapRuntimeForTest()
    val map = runtime.createMapState(BaseStyle.Empty)
    val adapter =
      object : PresentationTestAdapter() {
          override fun screenLocationFromPosition(position: Position) =
            DpOffset(position.longitude.dp, position.latitude.dp)
        }
        .apply { currentViewport = viewportFor(MapSnapshotRequest(300, 300)) }
    map.publishPresentation(map.reservePresentation(MapPresentationOwnerToken()), adapter)
    setContent {
      MapOverlayHost(
        mapState = map,
        overlay = {
          PointerPinButton(
            targetPosition = Position(longitude = -1000.0, latitude = 150.0),
            modifier = modifier.testTag("pin"),
          ) {
            Box(Modifier.size(24.dp).background(Color.Red))
          }
        },
      )
    }
    waitForIdle()

    val image = onNodeWithTag("pin").assertHasClickAction().captureToImage()
    assertEquals(Color.Red, image.toPixelMap()[image.width / 2, image.height / 2])
    map.close()
    runtime.close()
  }
}
