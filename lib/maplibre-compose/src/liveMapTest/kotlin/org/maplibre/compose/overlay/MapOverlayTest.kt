package org.maplibre.compose.overlay

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import org.maplibre.compose.map.MapPresentationOwnerToken
import org.maplibre.compose.map.MapSnapshotRequest
import org.maplibre.compose.map.PresentationTestAdapter
import org.maplibre.compose.map.mapRuntimeForTest
import org.maplibre.compose.map.viewportFor
import org.maplibre.compose.style.BaseStyle
import org.maplibre.spatialk.geojson.Position

@OptIn(ExperimentalTestApi::class)
class MapOverlayTest {
  @Test
  fun full_map_layout_and_control_insets_are_independent_before_a_viewport_exists() =
    runComposeUiTest {
      val runtime = mapRuntimeForTest()
      val map = runtime.createMapState(BaseStyle.Empty)
      var padding by
        mutableStateOf(PaddingValues(start = 40.dp, top = 20.dp, end = 60.dp, bottom = 30.dp))
      var rtl by mutableStateOf(false)
      setContent {
        CompositionLocalProvider(
          LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr
        ) {
          MapOverlayHost(
            mapState = map,
            cameraPadding = padding,
            modifier = Modifier.size(300.dp).testTag("map"),
            overlay = {
              Box(Modifier.matchParentSize().testTag("full"))
              Controls {
                Box(Modifier.size(10.dp).align(Alignment.TopStart).testTag("control"))
              }
              Controls(
                contentPadding = PaddingValues(0.dp),
                contentWindowInsets = WindowInsets(0),
              ) {
                Box(Modifier.size(10.dp).align(Alignment.BottomStart).testTag("override"))
              }
            },
          )
        }
      }
      waitForIdle()
      val bounds = onNodeWithTag("map").getUnclippedBoundsInRoot()
      assertEquals(bounds, onNodeWithTag("full").getUnclippedBoundsInRoot())
      assertEquals(bounds.left + 48.dp, onNodeWithTag("control").getUnclippedBoundsInRoot().left)
      assertEquals(bounds.top + 28.dp, onNodeWithTag("control").getUnclippedBoundsInRoot().top)
      assertEquals(bounds.left + 8.dp, onNodeWithTag("override").getUnclippedBoundsInRoot().left)
      runOnIdle {
        rtl = true
        padding = PaddingValues(start = 70.dp, top = 35.dp)
      }
      waitForIdle()
      assertEquals(bounds.right - 78.dp, onNodeWithTag("control").getUnclippedBoundsInRoot().right)
      assertEquals(bounds.top + 43.dp, onNodeWithTag("control").getUnclippedBoundsInRoot().top)
      assertEquals(bounds, onNodeWithTag("full").getUnclippedBoundsInRoot())
      map.close()
      runtime.close()
    }

  @Test
  fun camera_padding_is_map_relative_when_a_parent_consumes_system_insets() = runComposeUiTest {
    val runtime = mapRuntimeForTest()
    val map = runtime.createMapState(BaseStyle.Empty)
    setContent {
      Box(Modifier.padding(20.dp).consumeWindowInsets(PaddingValues(20.dp))) {
        MapOverlayHost(
          mapState = map,
          cameraPadding = PaddingValues(80.dp),
          modifier = Modifier.size(300.dp).testTag("map"),
          overlay = {
            Controls(
              contentWindowInsets =
                WindowInsets(left = 60.dp, top = 60.dp, right = 60.dp, bottom = 60.dp)
            ) {
              Box(Modifier.size(10.dp).testTag("camera"))
            }
            Controls(
              contentPadding = PaddingValues(0.dp),
              contentWindowInsets =
                WindowInsets(left = 60.dp, top = 60.dp, right = 60.dp, bottom = 60.dp),
            ) {
              Box(Modifier.size(10.dp).testTag("system"))
            }
          },
        )
      }
    }
    waitForIdle()
    val mapBounds = onNodeWithTag("map").getUnclippedBoundsInRoot()
    assertEquals(mapBounds.left + 88.dp, onNodeWithTag("camera").getUnclippedBoundsInRoot().left)
    assertEquals(mapBounds.left + 48.dp, onNodeWithTag("system").getUnclippedBoundsInRoot().left)
    map.close()
    runtime.close()
  }

  @Test
  fun geographic_placement_converts_nested_coordinates_when_the_parent_moves() = runComposeUiTest {
    val runtime = mapRuntimeForTest()
    val map = runtime.createMapState(BaseStyle.Empty)
    val adapter =
      object : PresentationTestAdapter() {
          override fun screenLocationFromPosition(position: Position) =
            DpOffset(position.longitude.dp, position.latitude.dp)
        }
        .apply { currentViewport = viewportFor(MapSnapshotRequest(300, 300)) }
    map.publishPresentation(map.reservePresentation(MapPresentationOwnerToken()), adapter)
    var offset by mutableStateOf(20.dp)
    val towards = PlacedTowardsState()
    setContent {
      MapOverlayHost(
        mapState = map,
        modifier = Modifier.size(300.dp).testTag("map"),
        overlay = {
          val overlay = this
          Box(Modifier.absoluteOffset(x = offset, y = offset).size(200.dp).padding(10.dp)) {
            overlay.AtPosition(Position(100.0, 100.0)) {
              Box(Modifier.size(10.dp).testTag("at"))
            }
            overlay.TowardsPosition(Position(1000.0, 100.0), state = towards) {
              Box(Modifier.size(10.dp).testTag("towards"))
            }
          }
        },
      )
    }
    waitForIdle()
    val bounds = onNodeWithTag("map").getUnclippedBoundsInRoot()
    val before = onNodeWithTag("at").getUnclippedBoundsInRoot()
    assertEquals(bounds.left + 95.dp, before.left)
    assertEquals(bounds.top + 95.dp, before.top)
    val beforePin = onNodeWithTag("towards").getUnclippedBoundsInRoot()
    runOnIdle { offset = 50.dp }
    waitForIdle()
    assertEquals(before, onNodeWithTag("at").getUnclippedBoundsInRoot())
    val afterPin = onNodeWithTag("towards").getUnclippedBoundsInRoot()
    kotlin.test.assertTrue(afterPin.left > beforePin.left)
    kotlin.test.assertTrue(afterPin.right <= bounds.left + 240.dp)
    kotlin.test.assertTrue(towards.isPlaced)
    map.close()
    runtime.close()
  }

  @Test
  fun overlay_composes_before_the_map_attaches() = runComposeUiTest {
    val mapState = mapRuntimeForTest().createMapState(baseStyle = BaseStyle.Empty)
    setContent {
      MapOverlayHost(
        overlay = {
          AtPosition(Position(0.0, 0.0)) {
            Box(Modifier.size(8.dp).testTag("at"))
          }
          TowardsPosition(Position(90.0, 0.0)) {
            Box(Modifier.size(8.dp).testTag("towards"))
          }
          Box(Modifier.size(8.dp).testTag("aligned").align(Alignment.TopStart))
        },
        mapState = mapState,
      )
    }
    waitForIdle()
    onNodeWithTag("aligned").assertIsDisplayed()
    onNodeWithTag("at").assertIsNotDisplayed()
    onNodeWithTag("towards").assertIsNotDisplayed()
    mapState.close()
  }

  @Test
  fun removing_a_placed_towards_child_resets_its_state() = runComposeUiTest {
    val mapState = mapRuntimeForTest().createMapState(baseStyle = BaseStyle.Empty)
    val state = PlacedTowardsState()
    var show by mutableStateOf(true)
    setContent {
      MapOverlayHost(
        overlay = {
          if (show) {
            TowardsPosition(Position(90.0, 0.0), state = state) {
              Box(Modifier.size(8.dp))
            }
          }
        },
        mapState = mapState,
      )
    }
    waitForIdle()
    runOnIdle {
      state.isPlaced = true
      show = false
    }
    waitForIdle()
    assertFalse(state.isPlaced)
    mapState.close()
  }
}
