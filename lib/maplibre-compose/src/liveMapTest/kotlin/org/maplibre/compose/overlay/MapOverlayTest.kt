package org.maplibre.compose.overlay

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
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
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.maplibre.compose.map.LocalMapState
import org.maplibre.compose.map.LocalViewport
import org.maplibre.compose.map.MapPresentationOwnerToken
import org.maplibre.compose.map.MapSnapshotRequest
import org.maplibre.compose.map.MapState
import org.maplibre.compose.map.PresentationTestAdapter
import org.maplibre.compose.map.mapRuntimeForTest
import org.maplibre.compose.map.viewportFor
import org.maplibre.compose.style.BaseStyle
import org.maplibre.spatialk.geojson.Position

@OptIn(ExperimentalTestApi::class)
class MapOverlayTest {
  @Test
  fun nested_overlays_and_reused_modifiers_use_the_nearest_map_context() = runComposeUiTest {
    val runtime = mapRuntimeForTest()
    val first = runtime.createMapState(BaseStyle.Empty)
    val second = runtime.createMapState(BaseStyle.Empty)
    for ((map, x) in listOf(first to 100.dp, second to 200.dp)) {
      val adapter =
        object : PresentationTestAdapter() {
            override fun screenLocationFromPosition(position: Position) = DpOffset(x, 100.dp)
          }
          .apply { currentViewport = viewportFor(MapSnapshotRequest(x.value.toInt() + 200, 300)) }
      map.publishPresentation(map.reservePresentation(MapPresentationOwnerToken()), adapter)
    }
    var outer by mutableStateOf(first)
    val sharedModifier =
      with(MapOverlayScopeInstance) { Modifier.placedAt(Position(0.0, 0.0)).size(10.dp) }

    @Composable
    fun Probe(tag: String, expected: MapState, padding: PaddingValues) {
      val map = LocalMapState.current
      val viewport = LocalViewport.current
      val cameraPadding = LocalCameraPadding.current
      SideEffect {
        assertSame(expected, map)
        assertEquals(expected.viewport, viewport)
        assertEquals(padding, cameraPadding)
      }
      Column {
        DefaultControls(contentWindowInsets = WindowInsets(0)) {
          GeographicLayout { Box(sharedModifier.testTag(tag)) }
        }
      }
    }

    setContent {
      assertNull(LocalMapState.current)
      assertNull(LocalViewport.current)
      MapOverlayHost(
        mapState = outer,
        cameraPadding = PaddingValues(20.dp),
        modifier = Modifier.size(300.dp).testTag("outer"),
        overlay = {
          Probe("first", outer, PaddingValues(20.dp))
          MapOverlayHost(
            mapState = second,
            cameraPadding = PaddingValues(40.dp),
            modifier = Modifier.absoluteOffset(x = 30.dp).size(250.dp).testTag("inner"),
            overlay = { Probe("second", second, PaddingValues(40.dp)) },
          )
          Probe("after", outer, PaddingValues(20.dp))
        },
      )
    }
    waitForIdle()
    val outerBounds = onNodeWithTag("outer").getUnclippedBoundsInRoot()
    val innerBounds = onNodeWithTag("inner").getUnclippedBoundsInRoot()
    assertEquals(outerBounds.left + 95.dp, onNodeWithTag("first").getUnclippedBoundsInRoot().left)
    assertEquals(innerBounds.left + 195.dp, onNodeWithTag("second").getUnclippedBoundsInRoot().left)
    assertEquals(outerBounds.left + 95.dp, onNodeWithTag("after").getUnclippedBoundsInRoot().left)
    runOnIdle { outer = second }
    waitForIdle()
    assertEquals(outerBounds.left + 195.dp, onNodeWithTag("first").getUnclippedBoundsInRoot().left)
    assertEquals(outerBounds.left + 195.dp, onNodeWithTag("after").getUnclippedBoundsInRoot().left)
    first.close()
    second.close()
    runtime.close()
  }

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
              DefaultControls(contentWindowInsets = WindowInsets(top = 24.dp)) {
                Box(Modifier.size(10.dp).align(Alignment.TopStart).testTag("control"))
              }
              DefaultControls(
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
      assertEquals(bounds.top + 32.dp, onNodeWithTag("control").getUnclippedBoundsInRoot().top)
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
            DefaultControls(
              contentWindowInsets =
                WindowInsets(left = 60.dp, top = 60.dp, right = 60.dp, bottom = 60.dp)
            ) {
              Box(Modifier.size(10.dp).testTag("camera"))
            }
            DefaultControls(
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
          GeographicLayout(
            Modifier.absoluteOffset(x = offset, y = offset).size(200.dp).padding(10.dp)
          ) {
            Box(Modifier.placedAt(Position(100.0, 100.0)).size(10.dp).testTag("at"))
            Box(
              Modifier.placedTowards(Position(1000.0, 100.0), state = towards)
                .size(10.dp)
                .testTag("towards")
            )
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
  fun placement_is_parent_data_and_child_modifier_order_does_not_change_the_region() =
    runComposeUiTest {
      val runtime = mapRuntimeForTest()
      val map = runtime.createMapState(BaseStyle.Empty)
      val adapter =
        object : PresentationTestAdapter() {
            override fun screenLocationFromPosition(position: Position) = DpOffset(100.dp, 100.dp)
          }
          .apply { currentViewport = viewportFor(MapSnapshotRequest(300, 300)) }
      map.publishPresentation(map.reservePresentation(MapPresentationOwnerToken()), adapter)
      setContent {
        MapOverlayHost(
          mapState = map,
          modifier = Modifier.size(300.dp).testTag("map"),
          overlay = {
            val position = Position(0.0, 0.0)
            Box(Modifier.placedAt(position).padding(10.dp).size(20.dp)) {
              Box(Modifier.size(20.dp).testTag("placement-first"))
            }
            Box(Modifier.padding(10.dp).size(20.dp).placedAt(position)) {
              Box(Modifier.size(20.dp).testTag("placement-last"))
            }
            Column(Modifier.placedAt(position, Alignment.TopStart).testTag("compound")) {
              Box(Modifier.size(10.dp))
              Box(Modifier.size(20.dp))
            }
            // A saved geographic modifier on a grandchild is not interpreted by an ordinary Box.
            val misplaced = Modifier.placedAt(position)
            Box(Modifier.size(50.dp).align(Alignment.BottomEnd).testTag("ordinary-parent")) {
              Box(misplaced.size(10.dp).testTag("ordinary-child"))
            }
          },
        )
      }
      waitForIdle()
      val mapBounds = onNodeWithTag("map").getUnclippedBoundsInRoot()
      val first = onNodeWithTag("placement-first").getUnclippedBoundsInRoot()
      assertEquals(first, onNodeWithTag("placement-last").getUnclippedBoundsInRoot())
      assertEquals(mapBounds.left + 90.dp, first.left)
      assertEquals(mapBounds.top + 90.dp, first.top)
      val compound = onNodeWithTag("compound").getUnclippedBoundsInRoot()
      assertEquals(mapBounds.left + 100.dp, compound.left)
      assertEquals(20.dp, compound.right - compound.left)
      assertEquals(30.dp, compound.bottom - compound.top)
      val ordinary = onNodeWithTag("ordinary-parent").getUnclippedBoundsInRoot()
      assertEquals(mapBounds.right - 50.dp, ordinary.left)
      val ordinaryChild = onNodeWithTag("ordinary-child").getUnclippedBoundsInRoot()
      assertEquals(ordinary.left, ordinaryChild.left)
      assertEquals(ordinary.top, ordinaryChild.top)
      map.close()
      runtime.close()
    }

  @Test
  fun overlay_composes_before_the_map_attaches() = runComposeUiTest {
    val mapState = mapRuntimeForTest().createMapState(baseStyle = BaseStyle.Empty)
    setContent {
      MapOverlayHost(
        overlay = {
          Box(Modifier.placedAt(Position(0.0, 0.0)).size(8.dp).testTag("at"))
          Box(Modifier.placedTowards(Position(90.0, 0.0)).size(8.dp).testTag("towards"))
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
  fun placed_towards_updates_and_releases_state_without_removing_the_child() = runComposeUiTest {
    val runtime = mapRuntimeForTest()
    val map = runtime.createMapState(BaseStyle.Empty)
    val adapter =
      object : PresentationTestAdapter() {
          override fun screenLocationFromPosition(position: Position) =
            DpOffset(position.longitude.dp, position.latitude.dp)
        }
        .apply { currentViewport = viewportFor(MapSnapshotRequest(300, 300)) }
    map.publishPresentation(map.reservePresentation(MapPresentationOwnerToken()), adapter)
    val first = PlacedTowardsState()
    val second = PlacedTowardsState()
    var state by mutableStateOf(first)
    var target by mutableStateOf(Position(1000.0, 150.0))
    var placed by mutableStateOf(true)
    setContent {
      MapOverlayHost(
        mapState = map,
        modifier = Modifier.size(300.dp),
        overlay = {
          Box(
            (if (placed) Modifier.placedTowards(target, state) else Modifier)
              .size(10.dp)
              .testTag("pin")
          )
        },
      )
    }
    waitForIdle()
    assertTrue(first.isPlaced)
    onNodeWithTag("pin").assertIsDisplayed()
    runOnIdle { state = second }
    waitForIdle()
    assertFalse(first.isPlaced)
    assertTrue(second.isPlaced)
    runOnIdle { target = Position(150.0, 150.0) }
    waitForIdle()
    assertFalse(second.isPlaced)
    onNodeWithTag("pin").assertIsNotDisplayed()
    runOnIdle { target = Position(1000.0, 150.0) }
    waitForIdle()
    assertTrue(second.isPlaced)
    runOnIdle { placed = false }
    waitForIdle()
    assertFalse(second.isPlaced)
    onNodeWithTag("pin").assertIsDisplayed()
    map.close()
    runtime.close()
  }
}
